package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 二进制参数跨桥回归（大备份包 OOM 修复 A 期）。
 *
 * 旧实现把字节 base64 后拼进 JS 源码求值（`__ximeHost.b64("<1.33N 字符串>")`），
 * N 字节包在宿主侧同时驻留多份 1.33N 字符串（实测存活集约 7 倍包体、分配总量 ~10.7 倍），
 * 30MB 级备份包在 192MB 堆上直接 OOM。
 *
 * 新实现：表达式只带槽下标（`__ximeHost.bin(i)`），字节经宿主槽原生跨桥一次
 * （Kotlin ByteArray → JS Uint8Array），表达式长度与包体大小无关。
 */
class JsBinaryArgBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val pluginJs = """
        globalThis.plugin = {
          echoBytes: function (payload) {
            var b = payload.archive;
            var sum = 0;
            for (var i = 0; i < b.length; i++) { sum = (sum * 31 + b[i]) % 2147483647; }
            return {
              len: b.length, sum: sum, first: b[0], last: b[b.length - 1],
              isU8: (b instanceof Uint8Array)
            };
          },
          echoNested: function (payload) {
            var a = payload.list[0];
            return {
              aLen: a.length, mid: a[(a.length / 2) | 0], text: payload.list[1],
              isU8: (a instanceof Uint8Array)
            };
          },
          echoAsync: async function (payload) {
            await Promise.resolve(0);
            return { len: payload.archive.length, last: payload.archive[payload.archive.length - 1] };
          },
          slotProbe: function (n) {
            var v = __ximeHost.bin(n);
            return { type: typeof v, isNull: (v === null), isUndef: (v === undefined) };
          }
        };
    """.trimIndent()

    private fun makeRuntime(): JsScriptRuntime {
        val dir = tmp.newFolder("bin-arg-probe")
        File(dir, "main.js").writeText(pluginJs)
        return JsScriptRuntime(
            pluginId = "bin-arg-probe",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore()
        )
    }

    private fun payload(mb: Int): ByteArray = ByteArray(mb * 1024 * 1024) { (it % 251).toByte() }

    /** 与插件 JS 中同一套滚动哈希：断言字节级一致，而不只是长度。 */
    private fun rollingHash(bytes: ByteArray): Long {
        var sum = 0L
        for (b in bytes) sum = (sum * 31 + (b.toInt() and 0xFF)) % 2147483647L
        return sum
    }

    private fun toInt(v: Any?): Int = (v as Number).toInt()

    @Test(timeout = 60_000)
    fun `字节参数不进 JS 源码 表达式长度与包体无关`() {
        val big = payload(16)
        val sink = ArrayList<ByteArray>()
        val expr = JsScriptRuntime.kotlinToJs(mapOf("name" to "Xime.zip", "archive" to big), sink)

        assertTrue(
            "16MB 载荷的表达式必须是槽位引用而非内嵌载荷，实际长度=${expr.length}",
            expr.length < 200
        )
        assertTrue("表达式应引用二进制槽: $expr", expr.contains("__ximeHost.bin(0)"))
        assertEquals("字节应被收集到 sink", 1, sink.size)
        assertEquals("收集的字节应与原载荷一致", big.size, sink[0].size)
        assertTrue("收集的字节应与原载荷同内容", big.contentEquals(sink[0]))
    }

    @Test(timeout = 60_000)
    fun `嵌套容器中的字节按出现顺序分配槽下标`() {
        val sink = ArrayList<ByteArray>()
        val expr = JsScriptRuntime.kotlinToJs(
            mapOf("list" to listOf(payload(1), "文本", payload(1))), sink
        )

        assertEquals("嵌套字节都应被收集", 2, sink.size)
        assertTrue("首个字节槽下标为 0: $expr", expr.contains("__ximeHost.bin(0)"))
        assertTrue("第二个字节槽下标为 1: $expr", expr.contains("__ximeHost.bin(1)"))
        assertTrue("字符串仍以字面量传递: $expr", expr.contains("文本"))
    }

    @Test(timeout = 60_000)
    fun `大字节参数跨桥后字节一致（同步扩展点）`() {
        val runtime = makeRuntime()
        try {
            assertTrue("插件应能加载", runtime.load())
            val bytes = payload(2)
            val out = runtime.call("echoBytes", mapOf("archive" to bytes)) as Map<*, *>

            assertEquals("长度应一致", bytes.size, toInt(out["len"]))
            assertEquals("内容应逐字节一致", rollingHash(bytes), (out["sum"] as Number).toLong())
            assertEquals(bytes[0].toInt() and 0xFF, toInt(out["first"]))
            assertEquals(bytes.last().toInt() and 0xFF, toInt(out["last"]))
            assertEquals("JS 侧应拿到 Uint8Array", true, out["isU8"])
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `大字节参数跨桥后字节一致（异步扩展点）`() {
        val runtime = makeRuntime()
        try {
            assertTrue(runtime.load())
            val bytes = payload(2)
            val out = runtime.callAsync("echoAsync", mapOf("archive" to bytes)) as Map<*, *>

            assertEquals(bytes.size, toInt(out["len"]))
            assertEquals(bytes.last().toInt() and 0xFF, toInt(out["last"]))
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `字节与字符串混排的嵌套参数保持各自类型`() {
        val runtime = makeRuntime()
        try {
            assertTrue(runtime.load())
            val bytes = payload(1)
            val out = runtime.call(
                "echoNested", mapOf("list" to listOf(bytes, "文本内容"))
            ) as Map<*, *>

            assertEquals(bytes.size, toInt(out["aLen"]))
            assertEquals(bytes[bytes.size / 2].toInt() and 0xFF, toInt(out["mid"]))
            assertEquals("文本内容", out["text"]?.toString())
            assertEquals(true, out["isU8"])
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `调用结束后槽位清空不残留上一次调用的字节`() {
        val runtime = makeRuntime()
        try {
            assertTrue(runtime.load())
            // 先跑一次带字节的调用
            runtime.call("echoBytes", mapOf("archive" to payload(1)))

            // 无字节的调用不应再看到槽位内容
            val probe = runtime.call("slotProbe", 0) as Map<*, *>
            assertTrue(
                "槽位应已清空（拿到 null/undefined，而不是上次的字节）: $probe",
                probe["isNull"] == true || probe["isUndef"] == true
            )
            // 空槽映射为 JS null（JS 里 typeof null === 'object'），不是上一次的 Uint8Array
            assertEquals("object", probe["type"]?.toString())
            assertEquals("空槽必须是 null 而非残留字节", true, probe["isNull"])
        } finally {
            runtime.close()
        }
    }
}
