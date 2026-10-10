package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件设置 schema 的读取契约（设置类界面统一走 [rememberPluginSettingsSchema]）。
 *
 * 背景（v3.1.0 真机 record）：设置页在**组合期**同步调 `getSettingsSchema()`，
 * 插件此刻若正被停用/重载（`runtime.close()`），调用会落到正在关闭的 QuickJS 引擎上，
 * 抛 `QuickJsException: Already closed` 并被记成"[脚本错误] 调用 settings.schema"。
 * 现在组合期只读状态、读取在 IO，且任何插件侧失败都降级为空 schema（不抛给 UI）。
 */
class PluginSettingsSchemaTest {

    private class FakePlugin(private val nodes: List<UiNode>) : IPluginConfigurable {
        override fun getSettingsSchema(): List<UiNode> = nodes
    }

    @Test
    fun `正常返回插件 schema`() {
        val nodes = listOf(UiNode(type = UiNodeType.TEXT, key = "server", label = "服务器地址"))
        assertEquals(nodes, readPluginSettingsSchema(FakePlugin(nodes)))
    }

    @Test
    fun `插件实例缺失时返回空 schema`() {
        assertTrue(readPluginSettingsSchema(null).isEmpty())
    }

    @Test
    fun `插件读取抛异常时降级为空 schema 而不抛给 UI`() {
        val closed = object : IPluginConfigurable {
            override fun getSettingsSchema(): List<UiNode> =
                throw IllegalStateException("已关闭") // 对应 QuickJsException: Already closed.
        }
        assertTrue(readPluginSettingsSchema(closed).isEmpty())
    }
}
