package com.kingzcheung.xime.plugin.core.lua

import android.util.Log
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApiImpl
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Lua 脚本插件运行时。
 *
 * ## 沙箱与隔离
 * - **一个插件一个 state**：每个 [LuaScriptRuntime] 持有独立的 `Globals`（独立 Lua 状态），
 *   插件间全局环境（`_G` 变量）、`require` 模块缓存完全隔离；一个插件的脚本错误不影响其他插件。
 * - **危险库剥离**：不加载 io/os，剥离 luajava（Java 反射）、loadfile/dofile（任意文件加载）
 * - **受限 require**：只能从插件包 libs/ 目录加载 .lua 模块，禁止路径穿越与 Java 类
 * - **宿主白名单 API**：仅注入 `host`（见 [LuaHostApi]）：config/log/resource
 */
class LuaScriptRuntime(
    private val pluginId: String,
    private val pluginDir: File,
    private val entryScript: String,
    private val configStore: PluginConfigStore,
    private val hostApi: LuaHostApi? = null,
    private val wsHostApi: com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi? = null,
    private val httpHostApi: com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi? = null,
    private val cryptoHostApi: com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi? = null,
    private val sseHostApi: com.kingzcheung.xime.plugin.core.lua.http.SseHostApi? = null,
    private val quickSendHostApi: QuickSendHostApi? = null,
    private val clipboardHostApi: ClipboardHostApi? = null,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val callbackTimeoutMs: Long = CALLBACK_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "LuaRuntime"

        /** 插件业务调用（load/call/onLoad/onUnload）超时：覆盖同步 HTTP 最长 30s 与用户自定义
         *  timeout，超时后插件被标记中毒（不再执行任何 Lua 代码，直到宿主重载插件）。 */
        private const val CALL_TIMEOUT_MS = 180_000L

        /** 网络回调（SSE/WS 事件）超时：回调应短促，恶意死循环回调会占住执行线程。 */
        private const val CALLBACK_TIMEOUT_MS = 5_000L

        /** 候选词变换（hotPath 能力）超时：调用发生在按键路径，超时即回退原始候选
         *  （不中毒——由调用方按连续失败次数熔断；15ms 足够纯内存计算的插件完成）。 */
        const val TRANSFORM_TIMEOUT_MS = 15L

        /** 候选词变换响应候选总数上限（超限截断，防插件撑爆候选栏）。 */
        const val TRANSFORM_MAX_CANDIDATES = 20

        /** zlib.gunzip 解压输出上限（防压缩炸弹撑爆内存）。 */
        private const val MAX_GUNZIP_OUTPUT_BYTES = 16 * 1024 * 1024

        fun tableToMap(table: LuaValue): Map<String, LuaValue> {
            val result = LinkedHashMap<String, LuaValue>()
            if (!table.istable()) return result
            var k: LuaValue = LuaValue.NIL
            while (true) {
                val next = table.next(k)
                if (next.arg1().isnil()) break
                k = next.arg1()
                result[k.tojstring()] = next.arg(2)
            }
            return result
        }

        fun tableToList(table: LuaValue): List<LuaValue> {
            val result = mutableListOf<LuaValue>()
            if (!table.istable()) return result
            var k: LuaValue = LuaValue.NIL
            while (true) {
                val next = table.next(k)
                if (next.arg1().isnil()) break
                k = next.arg1()
                result.add(next.arg(2))
            }
            return result
        }

        /** Lua 值 → Java 对象（供 host.json.encode 使用）。 */
        fun tableToJava(value: LuaValue): Any? {
            return when {
                value.isnil() -> null
                value.isboolean() -> value.toboolean()
                value.isnumber() -> if (value.tonumber().isint()) value.toint() else value.todouble()
                value.isstring() -> value.tojstring()
                value.istable() -> {
                    if (value.length() > 0) {
                        val list = mutableListOf<Any?>()
                        for (i in 1..value.length()) {
                            list.add(tableToJava(value.get(i)))
                        }
                        list
                    } else {
                        val map = LinkedHashMap<String, Any?>()
                        tableToMap(value).forEach { (k, v) -> map[k] = tableToJava(v) }
                        map
                    }
                }
                else -> null
            }
        }

        /** Java 对象 → Lua 值（供 host.json.decode 使用，Map/List 转为 LuaTable）。 */
        fun javaToLua(value: Any?): LuaValue {
            return when (value) {
                null -> LuaValue.NIL
                is Map<*, *> -> {
                    val table = LuaTable()
                    value.forEach { (k, v) -> table.set(k.toString(), javaToLua(v)) }
                    table
                }
                is List<*> -> {
                    val table = LuaTable()
                    value.forEachIndexed { i, v -> table.set(i + 1, javaToLua(v)) }
                    table
                }
                is Boolean -> LuaValue.valueOf(value)
                is Number -> LuaValue.valueOf(value.toDouble())
                is String -> LuaValue.valueOf(value)
                else -> CoerceJavaToLua.coerce(value)
            }
        }
    }

    /** Lua 值 → 请求体字节：字符串按 UTF-8，Lua table 序列化为 JSON，ByteArray userdata 原样。 */
    private fun bodyToBytes(value: LuaValue): ByteArray? {
        return when {
            value.isnil() -> null
            // LuaString 必须先于 isstring() 判断：二进制字节流（如备份 zip）若走
            // tojstring→UTF-8 重编码会损坏；文本字符串两种路径结果一致
            value is LuaString -> luaToBytes(value)
            value.isstring() -> value.tojstring().toByteArray(Charsets.UTF_8)
            value.istable() -> SimpleJson.encode(tableToJava(value)).toByteArray(Charsets.UTF_8)
            else -> luaToBytes(value)
        }
    }

    /** 调试日志：Lua 参数类型 + 摘要值。 */
    private fun argTypeValue(value: LuaValue): String {
        return when {
            value.isnil() -> "nil"
            value.isstring() -> "str:${value.tojstring().take(40)}"
            value.isnumber() -> "num:${value.tojstring()}"
            value.istable() -> "table:${tableToJava(value)}"
            else -> value.typename()
        }
    }

    /** Lua 字符串（二进制可含 \0）或 ByteArray userdata → ByteArray。 */
    private fun luaToBytes(value: LuaValue): ByteArray? {
        if (value is LuaString) {
            val out = ByteArray(value.m_length)
            value.copyInto(0, out, 0, value.m_length)
            return out
        }
        return try {
            value.checkuserdata(ByteArray::class.java) as ByteArray
        } catch (e: Exception) {
            null
        }
    }

    private val api: LuaHostApi = hostApi ?: LuaHostApiImpl(pluginId, pluginDir, configStore)
    private val globals: Globals = buildSandbox()
    private val loadedModules = ConcurrentHashMap<String, LuaValue>()
    private val libsDir = File(pluginDir, "libs")

    /**
     * 插件 Lua 执行的专用线程：宿主 IO 协程线程池不被插件死循环耗尽。
     * 超时后线程被弃用（Java 无法强杀死循环线程，但影响被隔离在该线程内），
     * 插件标记中毒，后续调用直接失败，用户可在插件中心重载/卸载。
     */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xime-lua-$pluginId").apply { isDaemon = true }
    }

    /** 插件是否已中毒（执行超时）：true 后所有 Lua 执行入口直接失败。 */
    @Volatile
    private var poisoned = false

    /** 在专用线程内执行 Lua 代码并限时；超时返回 null（可选的调用方按失败处理）。 */
    private fun <T> runGuarded(timeoutMs: Long, poisonOnTimeout: Boolean, block: () -> T): T? {
        if (poisoned) return null
        val future = executor.submit(Callable(block))
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            if (poisonOnTimeout) poison()
            null
        } catch (e: Exception) {
            future.cancel(true)
            throw e
        }
    }

    private fun poison() {
        if (poisoned) return
        poisoned = true
        executor.shutdownNow()
        api.logError("插件执行超时（疑似死循环），已停止执行该插件，请重载或卸载")
    }

    /** ASR 插件后端设置的宿主结果回调（Lua 的 emit* 桥接目标）。 */
    @Volatile
    var asrResultCallback: com.kingzcheung.xime.plugin.core.api.AsrPluginListener? = null

    /** 宿主 WebSocket 白名单 API（app 层实现）。 */
    val wsApi: com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi? = wsHostApi

    private var pluginTable: LuaValue = LuaValue.NIL
    private var loaded = false

    /**
     * Lua 状态串行锁：LuaJ 的 [Globals] 非线程安全，而 SSE/WS 回调（OkHttp/OkHttp WebSocket
     * 后台线程）与业务调用（IO 协程轮询 getPanelState 等）会并发执行同一个 Lua 状态，
     * 竞争可导致回调执行失败（异常被静默吞掉）、Lua 变量写入丢失。
     * 所有 Lua 执行入口必须持此锁（Java 内置锁可重入，host API 内层再回调 Lua 不会死锁）。
     */
    private val luaLock = Any()

    /** Lua 侧注册的 WS 事件回调（host.ws.connect 的 callbacks 表）。 */
    @Volatile
    private var wsCallbacks: Map<String, LuaValue>? = null

    /** SSE 会话 → Lua 回调表（host.http.stream 的 callbacks），会话 id 为宿主返回句柄。 */
    private val sseCallbacks = ConcurrentHashMap<Int, Map<String, LuaValue>>()

    // ---- 下行事件（manifest capabilities.events 声明后启用） ----

    /** 订阅的事件类型（小写 snake_case）；空集合 = 未启用事件通道。 */
    @Volatile
    private var subscribedEvents: Set<String> = emptySet()

    /** 下行事件通道：conflated，只保最新，插件消费慢不积压。 */
    private var eventChannel: Channel<PluginEvent>? = null

    /** 事件消费协程域；close 时 cancel。 */
    private var eventScope: CoroutineScope? = null

    /**
     * 声明本插件订阅的事件类型（来自 manifest capabilities.events）。
     * 必须在 [load] 之前调用；空集合不建立通道（未声明插件零开销）。
     */
    fun initEvents(subscribed: Set<String>) {
        subscribedEvents = subscribed
        if (subscribed.isEmpty()) return
        if (eventChannel != null) return
        val channel = Channel<PluginEvent>(Channel.CONFLATED)
        eventChannel = channel
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        eventScope = scope
        scope.launch {
            for (event in channel) {
                invokeEventCallback(event)
            }
        }
    }

    /** 向插件投递事件：未声明该类型或未启用通道时静默丢弃。返回是否已投递。 */
    fun dispatchEvent(event: PluginEvent): Boolean {
        val channel = eventChannel ?: return false
        if (event.type !in subscribedEvents) return false
        return channel.trySend(event).isSuccess
    }

    /** 事件 → Lua onPluginEvent(type, payload)（网络回调同级超时，不中毒）。 */
    private fun invokeEventCallback(event: PluginEvent) {
        try {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) {
                    if (!loaded) return@runGuarded
                    val fn = pluginTable.get(LuaPluginContract.FN_ON_PLUGIN_EVENT)
                    if (!fn.isfunction()) return@runGuarded
                    fn.invoke(LuaValue.valueOf(event.type), payloadToLuaTable(event.payload))
                }
            }
        } catch (e: Exception) {
            api.log("onPluginEvent 回调失败: ${e.message}")
        }
    }

    /** payload 快照 → Lua table（仅基础类型，缺失字段为 nil）。
     *  Long 转为 double：luaj number 即 double（Lua 5.1），统计类累计值远低于 2^53 无精度损失。 */
    private fun payloadToLuaTable(payload: Map<String, Any?>): LuaValue {
        val table = LuaValue.tableOf()
        for ((key, value) in payload) {
            table.set(key, when (value) {
                null -> LuaValue.NIL
                is String -> LuaValue.valueOf(value)
                is Boolean -> LuaValue.valueOf(value)
                is Int -> LuaValue.valueOf(value)
                is Long -> LuaValue.valueOf(value.toDouble())
                is Double -> LuaValue.valueOf(value)
                else -> LuaValue.valueOf(value.toString())
            })
        }
        return table
    }

    // ---- 候选词变换（manifest capabilities.candidate_transform 声明后宿主按需同步调用） ----

    /**
     * 同步调用插件 transformCandidates(request)。
     *
     * 线程契约：调用方为 key-processing 线程（hotPath），本方法阻塞等待至多
     * [TRANSFORM_TIMEOUT_MS]（luaLock 竞争可能额外等待，由调用方熔断兜底）。
     * 超时/报错不中毒（插件可能只是偶发慢），由调用方按连续失败熔断。
     */
    fun transformCandidates(request: CandidateTransformRequest): CandidateTransformOutcome {
        if (!loaded) return CandidateTransformOutcome.NoResponse
        val reqTable = LuaValue.tableOf()
        reqTable.set("input_text", LuaValue.valueOf(request.inputText))
        reqTable.set("preedit", LuaValue.valueOf(request.preedit))
        reqTable.set("ascii_mode", LuaValue.valueOf(request.asciiMode))
        val cands = LuaValue.tableOf()
        request.candidates.forEachIndexed { i, c ->
            val t = LuaValue.tableOf()
            t.set("text", LuaValue.valueOf(c.text))
            t.set("comment", LuaValue.valueOf(c.comment))
            cands.set(i + 1, t)
        }
        reqTable.set("candidates", cands)
        return try {
            // 函数不存在 / 插件返回 nil → block 返回 NIL；runGuarded 返回 null 仅表示超时
            val result = runGuarded(TRANSFORM_TIMEOUT_MS, poisonOnTimeout = false) {
                synchronized(luaLock) {
                    if (!loaded) return@runGuarded LuaValue.NIL
                    val fn = pluginTable.get(LuaPluginContract.FN_TRANSFORM_CANDIDATES)
                    if (!fn.isfunction()) return@runGuarded LuaValue.NIL
                    fn.invoke(reqTable).arg1()
                }
            }
            when {
                result == null -> CandidateTransformOutcome.Failed
                result.isnil() -> CandidateTransformOutcome.NoResponse
                result.istable() -> parseTransformResponse(result)
                else -> CandidateTransformOutcome.Failed
            }
        } catch (e: Exception) {
            api.log("transformCandidates 调用失败: ${e.message}")
            CandidateTransformOutcome.Failed
        }
    }

    /** 解析 { candidates = { {engine_index=..} | {text=.., comment=..} } }。
     *  candidates 字段缺失/非 table = 格式错误（Failed）；
     *  空列表或全部项非法 = 不干预（NoResponse）。 */
    private fun parseTransformResponse(result: LuaValue): CandidateTransformOutcome {
        val listTable = result.get("candidates")
        if (!listTable.istable()) return CandidateTransformOutcome.Failed
        val items = mutableListOf<CandidateTransformItem>()
        for (v in tableToList(listTable)) {
            if (!v.istable()) continue
            val comment = v.get("comment").takeIf { it.isstring() }?.tojstring()
            val engineIdx = v.get("engine_index")
            val text = v.get("text")
            when {
                engineIdx.isnumber() ->
                    items.add(CandidateTransformItem(engineIndex = engineIdx.toint(), text = null, comment = comment))
                text.isstring() && text.tojstring().isNotEmpty() ->
                    items.add(CandidateTransformItem(engineIndex = null, text = text.tojstring(), comment = comment))
                else -> continue // 非法项丢弃（既非引擎引用也非文本候选）
            }
            if (items.size >= TRANSFORM_MAX_CANDIDATES) break
        }
        if (items.isEmpty()) return CandidateTransformOutcome.NoResponse
        return CandidateTransformOutcome.Success(items)
    }

    /** 把 SSE 会话的流事件转发为 Lua 回调（宿主后台线程调用）。 */
    private fun invokeSseCallback(sessionId: Int, name: String, text: String) {
        val callbacks = sseCallbacks[sessionId] ?: return
        val fn = callbacks[name] ?: return
        try {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) {
                    fn.invoke(LuaValue.valueOf(text))
                }
            }
        } catch (e: Exception) {
            api.log("host.http.stream $name 回调失败: ${e.message}")
        }
    }

    private val wsListener = object : com.kingzcheung.xime.plugin.core.lua.ws.WsHostListener {
        override fun onOpen() {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) { wsCallbacks?.get("onOpen")?.invoke() }
            }
        }

        override fun onMessage(text: String) {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) { wsCallbacks?.get("onMessage")?.invoke(LuaValue.valueOf(text)) }
            }
        }

        override fun onBinary(data: ByteArray) {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) { wsCallbacks?.get("onBinary")?.invoke(LuaString.valueOf(data)) }
            }
        }

        override fun onError(message: String) {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) { wsCallbacks?.get("onError")?.invoke(LuaValue.valueOf(message)) }
            }
        }

        override fun onClose() {
            runGuarded(callbackTimeoutMs, poisonOnTimeout = false) {
                synchronized(luaLock) { wsCallbacks?.get("onClose")?.invoke() }
            }
            wsCallbacks = null
        }
    }

    private fun buildSandbox(): Globals {
        // JsePlatform 自动配置 compiler 与 loader；随后剥离全部危险库
        val g = org.luaj.vm2.lib.jse.JsePlatform.standardGlobals()

        // 危险库剥离：io/os（文件与系统调用）、luajava（Java 反射）、loadfile/dofile（任意文件加载）、
        // debug（getregistry/getmetatable 可篡改宿主注入表与注册表，扩大攻击面）
        g.set("os", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("dofile", LuaValue.NIL)
        g.set("debug", LuaValue.NIL)

        g.set("print", luaFunction { args ->
            api.log(args.tojstring())
            LuaValue.NIL
        })

        // 受限 require：只能加载 libs/<name>.lua，禁止路径穿越与 java 类
        g.set("require", luaFunction { args ->
            val name = args.arg1().checkjstring()
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                throw LuaError("require 非法模块名: $name")
            }
            synchronized(luaLock) {
                synchronized(loadedModules) {
                    loadedModules[name]?.let { return@luaFunction it }
                    val moduleFile = File(libsDir, "$name.lua")
                    if (!moduleFile.exists()) {
                        throw LuaError("module '$name' not found in libs/")
                    }
                    val chunk = g.load(moduleFile.readText(), "@$name")
                    val result = chunk.call()
                    val module = result.takeIf { it.istable() } ?: LuaValue.TRUE
                    loadedModules[name] = module
                    module
                }
            }
        })

        // 宿主白名单 API（见 LuaHostApi SDK 接口）
        g.set(LuaPluginContract.GLOBAL_HOST, buildHostTable())
        return g
    }

    private fun buildHostTable(): LuaTable {
        val host = LuaTable()

        host.set("sdkVersion", api.sdkVersion)
        host.set("log", luaFunction { args ->
            api.log(args.tojstring())
            LuaValue.NIL
        })
        host.set("logError", luaFunction { args ->
            api.logError(args.tojstring())
            LuaValue.NIL
        })

        val config = LuaTable()
        config.set("get", luaFunction { args ->
            CoerceJavaToLua.coerce(api.configGet(args.arg1().checkjstring()))
        })
        config.set("set", luaFunction { args ->
            api.configSet(args.arg1().checkjstring(), args.arg(2).tojstring())
            LuaValue.TRUE
        })
        config.set("remove", luaFunction { args ->
            api.configRemove(args.arg1().checkjstring())
            LuaValue.TRUE
        })
        config.set("keys", luaFunction { _ ->
            val arr = LuaTable()
            api.configKeys().forEachIndexed { index, key -> arr.set(index + 1, key) }
            arr
        })
        host.set("config", config)

        val resource = LuaTable()
        resource.set("path", luaFunction { args ->
            CoerceJavaToLua.coerce(api.resourcePath(args.arg1().checkjstring()))
        })
        resource.set("list", luaFunction { args ->
            val arr = LuaTable()
            api.resourceList(args.arg1().checkjstring()).forEachIndexed { i, name ->
                arr.set(i + 1, name)
            }
            arr
        })
        host.set("resource", resource)

        val json = LuaTable()
        json.set("encode", luaFunction { args ->
            val obj = LuaScriptRuntime.tableToJava(args.arg1())
            CoerceJavaToLua.coerce(api.jsonEncode(obj))
        })
        json.set("decode", luaFunction { args ->
            javaToLua(api.jsonDecode(args.arg1().checkjstring()))
        })
        host.set("json", json)

        host.set("uuid", luaFunction { _ ->
            LuaValue.valueOf(api.uuid())
        })

        // 二进制原语：大端 int32（帧序号，负数按补码输出）与 gzip 压缩/解压（火山等二进制协议需要）
        val bin = LuaTable()
        bin.set("int32be", luaFunction { args ->
            val n = args.arg1().toint()
            LuaString.valueOf(
                byteArrayOf(
                    ((n ushr 24) and 0xFF).toByte(),
                    ((n ushr 16) and 0xFF).toByte(),
                    ((n ushr 8) and 0xFF).toByte(),
                    (n and 0xFF).toByte()
                )
            )
        })
        bin.set("uint32be", luaFunction { args ->
            val n = args.arg1().toint()
            LuaString.valueOf(
                byteArrayOf(
                    ((n ushr 24) and 0xFF).toByte(),
                    ((n ushr 16) and 0xFF).toByte(),
                    ((n ushr 8) and 0xFF).toByte(),
                    (n and 0xFF).toByte()
                )
            )
        })
        host.set("bin", bin)

        val zlib = LuaTable()
        zlib.set("gzip", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            try {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(data) }
                LuaString.valueOf(bos.toByteArray())
            } catch (e: Exception) {
                api.log("zlib.gzip failed: ${e.message}")
                LuaValue.NIL
            }
        })
        zlib.set("gunzip", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            try {
                val gzipIn = GZIPInputStream(data.inputStream())
                val buffer = ByteArray(8192)
                val bos = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = gzipIn.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_GUNZIP_OUTPUT_BYTES) {
                        throw IllegalStateException("gunzip 输出超过上限（${MAX_GUNZIP_OUTPUT_BYTES / 1024 / 1024}MB）")
                    }
                    bos.write(buffer, 0, n)
                }
                LuaString.valueOf(bos.toByteArray())
            } catch (e: Exception) {
                api.log("zlib.gunzip failed: ${e.message}")
                LuaValue.NIL
            }
        })
        host.set("zlib", zlib)

        // 通用 WebSocket 白名单 API（协议无关，ASR 等网络插件使用，见 WsHostApi）
        if (wsHostApi != null) {
            host.set("ws", buildWsTable())
        }

        // 通用 HTTP 白名单 API（协议无关，剪贴板同步等插件使用，见 HttpHostApi）。
        // 同步 request 依赖 httpHostApi，流式 stream 依赖 sseHostApi，两者各自判空注册，
        // 因此宿主任一提供时都要挂出 host.http 表（否则只配 SSE 的插件拿不到 stream）。
        if (httpHostApi != null || sseHostApi != null) {
            host.set("http", buildHttpTable())
        }

        // 加密/编码原语（S3 SigV4 签名等，见 CryptoHostApi）
        if (cryptoHostApi != null) {
            host.set("crypto", buildCryptoTable())
        }

        // ASR 结果回传桥：插件 Lua 解析结果后通知宿主后端（协议无关的接口桥）
        host.set("asr", buildAsrEmitTable())

        // 快捷发送只读 API（manifest quick_send_read 声明后注入，未声明 host.quickSend 不存在）
        if (quickSendHostApi != null) {
            host.set("quickSend", buildQuickSendTable())
        }

        // 剪贴板只读 API（manifest clipboard_read 声明后注入，未声明 host.clipboard 不存在）
        if (clipboardHostApi != null) {
            host.set("clipboard", buildClipboardTable())
        }

        return host
    }

    private fun buildQuickSendTable(): LuaTable {
        val quickSend = LuaTable()
        quickSend.set("list", luaFunction { _ ->
            val arr = LuaTable()
            quickSendHostApi?.list()?.forEachIndexed { i, item ->
                val m = LuaTable()
                // Long → double：luaj number 即 double（Lua 5.1 语义），毫秒时间戳远低于 2^53
                m.set("id", LuaValue.valueOf(item.id.toDouble()))
                m.set("text", LuaValue.valueOf(item.text))
                m.set("code", LuaValue.valueOf(item.code))
                m.set("timestamp", LuaValue.valueOf(item.timestamp.toDouble()))
                m.set("isPinned", LuaValue.valueOf(item.isPinned))
                arr.set(i + 1, m)
            }
            arr
        })
        return quickSend
    }

    private fun buildClipboardTable(): LuaTable {
        val clipboard = LuaTable()
        clipboard.set("get", luaFunction { _ ->
            val text = clipboardHostApi?.getText()
            if (text.isNullOrEmpty()) LuaValue.NIL else LuaValue.valueOf(text)
        })
        return clipboard
    }

    private fun buildWsTable(): LuaTable {
        val ws = LuaTable()

        ws.set("connect", luaFunction { args ->
            val url = args.arg1().tojstring()
            val headers = HashMap<String, String>()
            LuaScriptRuntime.tableToMap(args.arg(2)).forEach { (k, v) ->
                headers[k] = v.tojstring()
            }
            // 解析 callbacks 表：{ onOpen=fn, onMessage=fn, onBinary=fn, onError=fn, onClose=fn }
            val callbacks = args.arg(3)
            if (callbacks.istable()) {
                val map = HashMap<String, LuaValue>()
                for (name in listOf("onOpen", "onMessage", "onBinary", "onError", "onClose")) {
                    val fn = callbacks.get(name)
                    if (fn.isfunction()) map[name] = fn
                }
                wsCallbacks = map
            }
            CoerceJavaToLua.coerce(wsHostApi?.connect(url, headers, wsListener) ?: false)
        })
        ws.set("sendText", luaFunction { args ->
            wsHostApi?.sendText(args.arg1().tojstring())
            LuaValue.NIL
        })
        ws.set("sendBinary", luaFunction { args ->
            val data = luaToBytes(args.arg1())
            if (data != null) wsHostApi?.sendBinary(data)
            LuaValue.NIL
        })
        ws.set("close", luaFunction { _ ->
            wsHostApi?.close()
            LuaValue.NIL
        })
        ws.set("getState", luaFunction { _ ->
            LuaValue.valueOf(wsHostApi?.getState() ?: 0)
        })
        ws.set("lastError", luaFunction { _ ->
            CoerceJavaToLua.coerce(wsHostApi?.lastError())
        })
        return ws
    }

    private fun buildHttpTable(): LuaTable {
        val http = LuaTable()

        http.set("request", luaFunction { args ->
            val method = args.arg1().tojstring()
            val url = args.arg(2).tojstring()
            val headers = HashMap<String, String>()
            LuaScriptRuntime.tableToMap(args.arg(3)).forEach { (k, v) ->
                headers[k] = v.tojstring()
            }
            val bodyArg = args.arg(4)
            val body: ByteArray? = bodyToBytes(bodyArg)
            Log.d("LuaHttpBridge", "[$pluginId] request(method=$method url=$url nargs=${args.narg()} bodyType=" +
                if (bodyArg.isnil()) "nil" else bodyArg.typename() +
                " body=${body?.toString(Charsets.UTF_8)?.take(300) ?: "<空>"}")
            val timeoutMillis = args.arg(5).optint(0)?.takeIf { it > 0 }
            val response = httpHostApi?.request(method, url, headers, body, timeoutMillis)
            if (response == null) {
                CoerceJavaToLua.coerce(null)
            } else {
                val table = LuaTable()
                table.set("status", response.status)
                val headerTable = LuaTable()
                response.headers.forEach { (k, v) -> headerTable.set(k, v) }
                table.set("headers", headerTable)
                table.set("body", LuaString.valueOf(response.body))
                table.set("text", response.body.toString(Charsets.UTF_8))
                table
            }
        })
        http.set("lastError", luaFunction { _ ->
            CoerceJavaToLua.coerce(httpHostApi?.lastError())
        })

        // SSE 流式（异步回调模型，见 SseHostApi）。流事件经 sseCallbacks 表回调 Lua 函数。
        if (sseHostApi != null) {
            http.set("stream", luaFunction { args ->
                val url = args.arg1().tojstring()
                val headers = HashMap<String, String>()
                LuaScriptRuntime.tableToMap(args.arg(2)).forEach { (k, v) ->
                    headers[k] = v.tojstring()
                }
                val callbacks = args.arg(3)
                val cb = HashMap<String, LuaValue>()
                if (callbacks.istable()) {
                    for (name in listOf("onData", "onDone", "onError")) {
                        val fn = callbacks.get(name)
                        if (fn.isfunction()) cb[name] = fn
                    }
                }
                val arg4 = args.arg(4)
                val arg5 = args.arg(5)
                val arg6 = args.arg(6)
                // 兼容多风格调用：stream(url, headers, callbacks[, timeout][, method][, body])，
                // 也兼容 stream(url, headers, callbacks, method) 等省略 timeout/body 的写法。
                val timeoutMillis: Int? = when {
                    arg4.isnumber() -> arg4.toint().takeIf { it > 0 }
                    arg5.isnumber() -> arg5.toint().takeIf { it > 0 }
                    arg4.isstring() && arg4.tojstring().toIntOrNull() != null ->
                        arg4.tojstring().toInt().takeIf { it > 0 }
                    arg5.isstring() && arg5.tojstring().toIntOrNull() != null ->
                        arg5.tojstring().toInt().takeIf { it > 0 }
                    else -> null
                }
                val method: String = when {
                    arg4.isstring() && arg4.tojstring().toIntOrNull() == null ->
                        arg4.tojstring().uppercase()
                    arg5.isstring() && arg5.tojstring().toIntOrNull() == null ->
                        arg5.tojstring().uppercase()
                    else -> "GET"
                }
                val body: ByteArray? = when {
                    !arg6.isnil() -> bodyToBytes(arg6)
                    arg5.istable() -> bodyToBytes(arg5)
                    arg4.istable() -> bodyToBytes(arg4)
                    else -> null
                }
                Log.d("LuaHttpBridge", "[$pluginId] stream(url=$url method=$method nargs=${args.narg()} " +
                    "arg4=${argTypeValue(arg4)} arg5=${argTypeValue(arg5)} arg6=${argTypeValue(arg6)} " +
                    "body=${body?.toString(Charsets.UTF_8)?.take(300) ?: "<空>"}")
                var sessionId = -1
                val listener = object : com.kingzcheung.xime.plugin.core.lua.http.SseHostListener {
                    override fun onData(text: String) { invokeSseCallback(sessionId, "onData", text) }
                    override fun onDone(fullText: String) { invokeSseCallback(sessionId, "onDone", fullText) }
                    override fun onError(message: String) { invokeSseCallback(sessionId, "onError", message) }
                }
                sessionId = sseHostApi.connect(url, headers, listener, timeoutMillis, method, body)
                if (sessionId >= 0) {
                    sseCallbacks[sessionId] = cb
                }
                CoerceJavaToLua.coerce(sessionId)
            })
            http.set("closeStream", luaFunction { args ->
                val id = args.arg1().toint()
                sseCallbacks.remove(id)
                sseHostApi.close(id)
                LuaValue.NIL
            })
        }
        return http
    }

    private fun buildCryptoTable(): LuaTable {
        val crypto = LuaTable()

        crypto.set("sha256", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            LuaString.valueOf(cryptoHostApi?.sha256(data) ?: return@luaFunction LuaValue.NIL)
        })
        crypto.set("hmacSha256", luaFunction { args ->
            val key = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            val data = luaToBytes(args.arg(2)) ?: return@luaFunction LuaValue.NIL
            LuaString.valueOf(cryptoHostApi?.hmacSha256(key, data) ?: return@luaFunction LuaValue.NIL)
        })
        crypto.set("hmacSha1", luaFunction { args ->
            val key = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            val data = luaToBytes(args.arg(2)) ?: return@luaFunction LuaValue.NIL
            LuaString.valueOf(cryptoHostApi?.hmacSha1(key, data) ?: return@luaFunction LuaValue.NIL)
        })
        crypto.set("hex", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            CoerceJavaToLua.coerce(cryptoHostApi?.hex(data))
        })
        crypto.set("base64", luaFunction { args ->
            val data = luaToBytes(args.arg1()) ?: return@luaFunction LuaValue.NIL
            CoerceJavaToLua.coerce(cryptoHostApi?.base64(data))
        })
        crypto.set("utcTime", luaFunction { args ->
            CoerceJavaToLua.coerce(cryptoHostApi?.utcTime(args.arg1().tojstring()))
        })
        crypto.set("epochSeconds", luaFunction { _ ->
            CoerceJavaToLua.coerce(cryptoHostApi?.epochSeconds() ?: return@luaFunction LuaValue.NIL)
        })
        return crypto
    }

    private fun buildAsrEmitTable(): LuaTable {
        val asr = LuaTable()

        asr.set("emitFinal", luaFunction { args ->
            asrResultCallback?.onFinal(args.arg1().tojstring())
            LuaValue.NIL
        })
        asr.set("emitPartial", luaFunction { args ->
            asrResultCallback?.onPartial(args.arg1().tojstring())
            LuaValue.NIL
        })
        asr.set("emitError", luaFunction { args ->
            asrResultCallback?.onError(args.arg1().tojstring())
            LuaValue.NIL
        })
        asr.set("emitState", luaFunction { args ->
            asrResultCallback?.onStateChanged(
                when (args.arg1().toint()) {
                    1 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.LISTENING
                    2 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.PROCESSING
                    3 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.ERROR
                    else -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.IDLE
                }
            )
            LuaValue.NIL
        })
        return asr
    }

    /** 加载入口脚本并调用插件导出表。 */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            runGuarded(callTimeoutMs, poisonOnTimeout = true) {
                synchronized(luaLock) {
                    if (loaded) return@runGuarded true
                    val entryFile = File(pluginDir, entryScript)
                    if (!entryFile.exists()) {
                        Log.e(TAG, "Entry script not found: ${entryFile.absolutePath}")
                        return@runGuarded false
                    }
                    val chunk = globals.load(entryFile.readText(), "@$entryScript")
                    val result = chunk.call()
                    pluginTable = result.takeIf { it.istable() } ?: LuaValue.NIL
                    loaded = true
                    Log.d(TAG, "Plugin $pluginId loaded from $entryScript")
                    true
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Lua plugin $pluginId", e)
            false
        }
    }

    fun callOnLoad() {
        if (!loaded) return
        try {
            val fn = synchronized(luaLock) { pluginTable.get(LuaPluginContract.FN_ON_LOAD) }
            if (!fn.isfunction()) return
            runGuarded(callTimeoutMs, poisonOnTimeout = true) {
                synchronized(luaLock) { fn.invoke() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onLoad failed for $pluginId", e)
        }
    }

    fun callOnUnload() {
        if (!loaded) return
        try {
            val fn = synchronized(luaLock) { pluginTable.get(LuaPluginContract.FN_ON_UNLOAD) }
            if (!fn.isfunction()) return
            runGuarded(callTimeoutMs, poisonOnTimeout = true) {
                synchronized(luaLock) { fn.invoke() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onUnload failed for $pluginId", e)
        }
    }

    /** 调用插件导出的函数，返回 LuaValue 结果；不存在、出错或超时返回 NIL。 */
    fun call(name: String, vararg args: LuaValue): LuaValue {
        if (!loaded) return LuaValue.NIL
        return try {
            runGuarded(callTimeoutMs, poisonOnTimeout = true) {
                synchronized(luaLock) {
                    val fn = pluginTable.get(name)
                    if (!fn.isfunction()) {
                        Log.w(TAG, "Plugin $pluginId does not export '$name'")
                        return@runGuarded LuaValue.NIL
                    }
                    if (args.isEmpty()) {
                        fn.invoke().arg1()
                    } else {
                        fn.invoke(args).arg1()
                    }
                }
            } ?: LuaValue.NIL
        } catch (e: Exception) {
            Log.e(TAG, "Call '$name' failed for $pluginId: ${e.message}", e)
            api.log("Call '$name' failed: ${e.message}")
            LuaValue.NIL
        }
    }

    fun close() {
        try {
            callOnUnload()
        } finally {
            eventScope?.cancel()
            eventScope = null
            eventChannel?.close()
            eventChannel = null
            subscribedEvents = emptySet()
            executor.shutdownNow()
            loadedModules.clear()
            sseCallbacks.clear()
            wsCallbacks = null
            pluginTable = LuaValue.NIL
            loaded = false
        }
    }

    private fun luaFunction(body: (Varargs) -> Varargs): LuaValue {
        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = body(args)
        }
    }
}
