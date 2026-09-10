package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证 typing-stats 插件端到端统计口径（Lua 侧）：
 * - 正常打字上屏（is_paste=false）按差值累计字数与提交次数
 * - 粘贴上屏（is_paste=true）不计字数/提交次数，但事件照收推进差值基准
 *   （last_seen 前移，粘贴后的正常输入不重复累计粘贴部分）
 */
class LuaTypingStatsPluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** 固定时间源：今日 = 20260910（沙箱无 os，插件经 host.crypto.utcTime 取日期）。 */
    private class FixedClockCrypto : CryptoHostApi {
        override fun utcTime(format: String): String =
            if (format == "YYYYMMDD") "20260910" else "20260910T120000Z"
        override fun epochSeconds(): Long = 0L
        override fun sha256(data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String = ""
    }

    private fun newRuntime(store: InMemoryConfigStore): LuaScriptRuntime {
        val dir = File("../plugins/typing-stats")
        assertTrue("typing-stats 插件目录应存在: ${dir.absolutePath}", dir.exists())
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.typing_stats",
            dir,
            "main.lua",
            store,
            cryptoHostApi = FixedClockCrypto()
        )
        // 事件通道必须在 load 之前声明（与宿主 PluginLifecycleManager 同序）
        runtime.initEvents(setOf("input_changed", "text_committed"))
        assertTrue("main.lua 应能加载", runtime.load())
        return runtime
    }

    private fun committedEvent(sessionChars: Long, isPaste: Boolean) = PluginEvent(
        PluginEvent.TYPE_TEXT_COMMITTED,
        mapOf(
            PluginEvent.FIELD_COMMITTED_TEXT to "文本",
            PluginEvent.FIELD_SESSION_TOTAL_CHARS to sessionChars,
            PluginEvent.FIELD_SESSION_TOTAL_COMMITS to 1L,
            PluginEvent.FIELD_IS_PASTE to isPaste,
        )
    )

    /** 事件经 conflated 通道异步消费：轮询 config 直到 last_seen 前移到期望值。 */
    private fun awaitLastSeen(store: InMemoryConfigStore, expected: Long, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (store.get("last_seen_chars") == expected.toString()) return
            Thread.sleep(20)
        }
        assertTrue("等待 last_seen_chars=$expected 超时，实际=${store.get("last_seen_chars")}", false)
    }

    @Test
    fun `打字累计而粘贴不计入且不破坏后续差值`() {
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store)

        // 正常打字：session 推进 5 字（首次差值按 0，防插件重载重复累计——既有设计语义）
        assertTrue(runtime.dispatchEvent(committedEvent(5L, isPaste = false)))
        awaitLastSeen(store, 5L)
        assertEquals("0", store.get("total_chars"))
        assertEquals("1", store.get("total_commits"))

        // 第二笔正常打字：差值 4 正常累计
        assertTrue(runtime.dispatchEvent(committedEvent(9L, isPaste = false)))
        awaitLastSeen(store, 9L)
        assertEquals("4", store.get("total_chars"))
        assertEquals("2", store.get("total_commits"))
        assertTrue(
            "daily 应含今日 4 字: ${store.get("daily")}",
            store.get("daily")?.contains("20260910") == true && store.get("daily")!!.contains("4")
        )

        // 粘贴 12 字：不计字数/次数，但 last_seen 推进到 21（差值基准不被粘贴破坏）
        assertTrue(runtime.dispatchEvent(committedEvent(21L, isPaste = true)))
        awaitLastSeen(store, 21L)
        assertEquals("粘贴不应计入累计字数", "4", store.get("total_chars"))
        assertEquals("粘贴不应计入提交次数", "2", store.get("total_commits"))

        // 粘贴后继续打字 3 字：只累计 3，不把粘贴的 12 重复算入
        assertTrue(runtime.dispatchEvent(committedEvent(24L, isPaste = false)))
        awaitLastSeen(store, 24L)
        assertEquals("7", store.get("total_chars"))
        assertEquals("3", store.get("total_commits"))
    }
}
