package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.luaj.vm2.LuaValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * typing-stats 演示插件：验证 capabilities.events 下行事件的真实端到端行为。
 * - text_committed 增量累计（首次为 0、差值累计、宿主重启归零后 delta 翻转）
 * - input_changed 内存态进入面板
 * - host.config 持久化（重载后 last_seen 防重复累计）
 */
class LuaTypingStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockCrypto : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(20)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String = ""
        override fun utcTime(format: String): String = "20260828"
        override fun epochSeconds(): Long = 1767225600
    }

    private fun newRuntime(store: PluginConfigStore): LuaScriptRuntime {
        val dir = File("../plugins/typing-stats")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.typing_stats",
            dir,
            "main.lua",
            store,
            cryptoHostApi = MockCrypto()
        )
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED, PluginEvent.TYPE_TEXT_COMMITTED))
        assertTrue("main.lua 应能加载", runtime.load())
        return runtime
    }

    private fun dispatchCommitted(runtime: LuaScriptRuntime, text: String, totalChars: Long, totalCommits: Long) {
        assertTrue(
            runtime.dispatchEvent(
                PluginEvent(
                    PluginEvent.TYPE_TEXT_COMMITTED,
                    mapOf(
                        PluginEvent.FIELD_COMMITTED_TEXT to text,
                        PluginEvent.FIELD_SESSION_TOTAL_CHARS to totalChars,
                        PluginEvent.FIELD_SESSION_TOTAL_COMMITS to totalCommits,
                    )
                )
            )
        )
    }

    private fun awaitPanelUi(runtime: LuaScriptRuntime, contains: String): Map<*, *>? {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val state = LuaScriptRuntime.tableToMap(runtime.call("getPanelState", LuaValue.valueOf("")))
            val ui = state["ui"]
            if (ui != null && ui.istable()) {
                var k: LuaValue = LuaValue.NIL
                while (true) {
                    val next = ui.next(k)
                    if (next.arg1().isnil()) break
                    k = next.arg1()
                    val node = LuaScriptRuntime.tableToJava(next.arg(2)) as? Map<*, *>
                    if (node != null && node.values.any { it?.toString()?.contains(contains) == true }) return node
                }
            }
            Thread.sleep(50)
        }
        throw AssertionError("面板 ui 未出现含 '$contains' 的节点")
    }

    /** 等待出现 value 匹配的 metric 节点（今日/累计共享同一计数时取其一即可）。 */
    private fun awaitMetricValue(runtime: LuaScriptRuntime, value: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val state = LuaScriptRuntime.tableToMap(runtime.call("getPanelState", LuaValue.valueOf("")))
            val ui = state["ui"]
            if (ui != null && ui.istable()) {
                var k: LuaValue = LuaValue.NIL
                while (true) {
                    val next = ui.next(k)
                    if (next.arg1().isnil()) break
                    k = next.arg1()
                    val node = LuaScriptRuntime.tableToJava(next.arg(2)) as? Map<*, *>
                    if (node != null && node["type"] == "metric" && node["value"]?.toString() == value) return
                }
            }
            Thread.sleep(50)
        }
        throw AssertionError("面板 ui 未出现 metric value=$value")
    }

    @Test
    fun `首次事件不回溯历史，之后按差值累计`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)

        // 首次快照：last_seen 未记录 → delta = 0，不累计
        dispatchCommitted(runtime, "你好", totalChars = 100, totalCommits = 10)
        // 第二次快照：delta = 125 - 100 = 25
        dispatchCommitted(runtime, "世界你好", totalChars = 125, totalCommits = 11)

        awaitMetricValue(runtime, "25")
        assertEquals("25", store.get("total_chars"))
        // daily JSON 形如 {"20260828":25}
        assertEquals(
            "25",
            Regex("\"20260828\":(\\d+)").find(store.get("daily")!!)?.groupValues?.get(1)
        )
        runtime.close()
    }

    @Test
    fun `reset action 清零统计`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)

        // 首次快照 delta=0；第二次快照累计"你好世界"4 字
        dispatchCommitted(runtime, "你好", totalChars = 30, totalCommits = 1)
        dispatchCommitted(runtime, "你好世界", totalChars = 34, totalCommits = 2)
        awaitMetricValue(runtime, "4")

        runtime.call("onPanelAction", LuaValue.valueOf("reset"))
        awaitMetricValue(runtime, "0")
        assertEquals("0", store.get("total_chars"))
        runtime.close()
    }

    @Test
    fun `宿主重启后 session 归零，负差值翻转为增量`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)

        dispatchCommitted(runtime, "a", totalChars = 100, totalCommits = 1)
        dispatchCommitted(runtime, "b", totalChars = 110, totalCommits = 2)
        awaitMetricValue(runtime, "10")

        // 模拟宿主进程重启：session 累计从 0 重新开始
        dispatchCommitted(runtime, "c", totalChars = 5, totalCommits = 1)
        awaitMetricValue(runtime, "15")
        assertEquals("15", store.get("total_chars"))
        runtime.close()
    }

    @Test
    fun `input_changed 只进面板不落盘`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)

        assertTrue(
            runtime.dispatchEvent(
                PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "niha"))
            )
        )
        awaitPanelUi(runtime, "正在输入: niha")
        // 内存态不产生持久化副作用
        assertTrue(store.get("total_chars") == null || store.get("total_chars") == "0")
        runtime.close()
    }

    @Test
    fun `称号随累计字数进阶并显示升级提示`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)

        // 首次快照 delta=0，累计 0 → 新手（含 🌱 徽章）
        dispatchCommitted(runtime, "你好", totalChars = 100, totalCommits = 1)
        awaitPanelUi(runtime, "🌱 新手")
        awaitPanelUi(runtime, "✨ 距「入门学徒」还差 1000 字")

        // 累计跨过 1000 → 入门学徒（🥉）
        dispatchCommitted(runtime, "再打", totalChars = 1100, totalCommits = 2)
        awaitPanelUi(runtime, "🥉 入门学徒")

        runtime.close()
    }

    @Test
    fun `重载后 last_seen 持久化，快照回退不重复累计`() {
        val store = InMemoryConfigStore()
        // 第一段生命周期
        var runtime = newRuntime(store)
        dispatchCommitted(runtime, "你好世界", totalChars = 50, totalCommits = 1)
        dispatchCommitted(runtime, "，再见", totalChars = 65, totalCommits = 2)
        awaitMetricValue(runtime, "15")
        runtime.close()

        // 第二段生命周期（插件重载）：session 快照继续增长（宿主未重启、插件重载）
        runtime = newRuntime(store)
        dispatchCommitted(runtime, "x", totalChars = 70, totalCommits = 3)
        awaitMetricValue(runtime, "20")
        assertEquals("20", store.get("total_chars"))
        assertEquals("70", store.get("last_seen_chars"))
        runtime.close()
    }
}
