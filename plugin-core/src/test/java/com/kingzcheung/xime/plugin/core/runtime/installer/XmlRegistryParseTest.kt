package com.kingzcheung.xime.plugin.core.runtime.installer

import com.kingzcheung.xime.plugin.core.api.ToolResult
import com.kingzcheung.xime.plugin.core.model.PluginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表 plugins.xml 解析回归测试。
 *
 * 根因背景：flushToDisk 写入时 capabilities 节点里的 JSON 经 escapeXml 转义
 * （`"` → `&quot;`），读取端此前未反转义就交给 JSON 解码 → 必然失败 →
 * capabilities 静默置 null。进程冷启动（手机重启/后台被杀重建）后所有插件
 * 能力声明丢失：事件订阅通道不建立（统计插件不统计、速度恒 0）、
 * tool.display 丢失（passive 面板退化成 direct 布局）。
 */
class XmlRegistryParseTest {

    /** 模拟 flushToDisk 实际写出的格式：capabilities 为 escapeXml 后的 JSON。 */
    private fun registryXml(capabilitiesNode: String?): String {
        val cap = capabilitiesNode?.let { "    <capabilities>$it</capabilities>\n" } ?: ""
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <plugins>
              <plugin>
                <id>com.kingzcheung.xime.plugin.typing_stats</id>
                <name>输入统计</name>
                <description>统计打字量</description>
                <versionCode>0</versionCode>
                <versionName>0.2.0</versionName>
                <path>/data/user/0/app/files/plugins/com.kingzcheung.xime.plugin.typing_stats/main.lua</path>
                <type>tool</type>
                <enabled>true</enabled>
                <installTime>1757289600000</installTime>
                <source>REMOTE</source>
                <iconResId>0</iconResId>
                <trustLevel>TRUSTED</trustLevel>
                <minHostVersion>2.8.0</minHostVersion>
                <entryScript>main.lua</entryScript>
                <manifestIcon>统</manifestIcon>
            $cap  </plugin>
            </plugins>
        """.trimIndent()
    }

    @Test
    fun `回归 - 转义过的 capabilities JSON 读取后能力声明完整`() {
        // 与 encodeCapabilities + escapeXml 的真实输出一致（passive 工具 + 事件订阅）
        val xml = registryXml(
            "{&quot;tool&quot;:{&quot;display&quot;:&quot;passive&quot;}," +
                "&quot;events&quot;:[&quot;input_changed&quot;,&quot;text_committed&quot;]}"
        )

        val plugin = XmlManager.parsePluginsXmlContent(xml).single()
        val cap = plugin.capabilities
        assertNotNull("转义 JSON 必须能解码出 capabilities（此前未反转义直接解码必失败）", cap)
        cap!!
        assertEquals(
            listOf("input_changed", "text_committed"),
            cap.events
        )
        assertEquals(ToolResult.PASSIVE, cap.tool?.display)
    }

    @Test
    fun `direct 显示面板的能力声明解析`() {
        val xml = registryXml("{&quot;tool&quot;:{&quot;display&quot;:&quot;direct&quot;}}")

        val plugin = XmlManager.parsePluginsXmlContent(xml).single()
        assertEquals(ToolResult.DIRECT, plugin.capabilities?.tool?.display)
        assertTrue("事件未声明时为空列表", plugin.capabilities?.events!!.isEmpty())
    }

    @Test
    fun `旧注册表无 capabilities 节点时为 null`() {
        val plugin = XmlManager.parsePluginsXmlContent(registryXml(null)).single()
        assertNull(plugin.capabilities)
    }

    @Test
    fun `损坏的 capabilities 解析失败不影响插件本身加载`() {
        val xml = registryXml("{不是合法JSON}")

        val plugin = XmlManager.parsePluginsXmlContent(xml).single()
        assertNull(plugin.capabilities)
        assertEquals("com.kingzcheung.xime.plugin.typing_stats", plugin.id)
        assertEquals("0.2.0", plugin.versionName)
    }

    @Test
    fun `注册表其余字段解析正确`() {
        val xml = registryXml(
            "{&quot;tool&quot;:{&quot;display&quot;:&quot;passive&quot;}}"
        )

        val plugin = XmlManager.parsePluginsXmlContent(xml).single()
        assertEquals("输入统计", plugin.name)
        assertEquals("tool", plugin.type)
        assertEquals(PluginSource.REMOTE, plugin.source)
        assertEquals("2.8.0", plugin.minHostVersion)
        assertEquals("main.lua", plugin.entryScript)
        assertEquals("统", plugin.manifestIcon)
        assertTrue(plugin.enabled)
    }

    @Test
    fun `toolbarButton 属性反转义解析`() {
        val xml = """
            <plugins>
              <plugin>
                <id>demo</id>
                <path>/p/main.lua</path>
                <toolbarButton id="demo:open" label="AI &amp; 翻译" action="open_panel"/>
              </plugin>
            </plugins>
        """.trimIndent()

        val button = XmlManager.parsePluginsXmlContent(xml).single().toolbarButtons.single()
        assertEquals("AI & 翻译", button.label)
    }
}
