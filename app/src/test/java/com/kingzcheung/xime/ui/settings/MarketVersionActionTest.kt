package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插件「版本历史」的动作判定（真机反馈："funasr 还存在多个版本已安装"）。
 *
 * 事实核对（adb + 市场索引）：本机 `files/plugins.json` 只有一条 funasr 记录、
 * 磁盘只有 `files/plugins/com.kingzcheung.xime.plugin.funasr_asr/`（manifest 3.1.1）；
 * 而索引里 funasr 有 3.1.1 / 3.1.0 / 3.0.0 三版 → 详情页 3 张卡。
 * 旧实现按**插件级** `installed` 判定 → 三张卡全写"已安装"。
 */
class MarketVersionActionTest {

    @Test
    fun `只有本机已装的那一版是已安装`() {
        // 本机 3.1.1、索引当前也是 3.1.1（无更新）
        assertEquals(
            PluginVersionAction.Installed,
            pluginVersionAction(installedVersion = "3.1.1", hasUpdate = false, currentVersion = "3.1.1", version = "3.1.1")
        )
        assertEquals(
            PluginVersionAction.Download,
            pluginVersionAction(installedVersion = "3.1.1", hasUpdate = false, currentVersion = "3.1.1", version = "3.1.0")
        )
        assertEquals(
            PluginVersionAction.Download,
            pluginVersionAction(installedVersion = "3.1.1", hasUpdate = false, currentVersion = "3.1.1", version = "3.0.0")
        )
    }

    @Test
    fun `索引当前版给更新 更老的版本给下载`() {
        // 本机 3.1.0、索引当前 3.1.1（有更新）
        assertEquals(
            PluginVersionAction.Update,
            pluginVersionAction(installedVersion = "3.1.0", hasUpdate = true, currentVersion = "3.1.1", version = "3.1.1")
        )
        assertEquals(
            PluginVersionAction.Installed,
            pluginVersionAction(installedVersion = "3.1.0", hasUpdate = true, currentVersion = "3.1.1", version = "3.1.0")
        )
        assertEquals(
            PluginVersionAction.Download,
            pluginVersionAction(installedVersion = "3.1.0", hasUpdate = true, currentVersion = "3.1.1", version = "3.0.0")
        )
    }

    @Test
    fun `未安装时每一版都是下载`() {
        for (v in listOf("3.1.1", "3.1.0", "3.0.0")) {
            assertEquals(
                PluginVersionAction.Download,
                pluginVersionAction(installedVersion = null, hasUpdate = false, currentVersion = "3.1.1", version = v)
            )
        }
    }

    @Test
    fun `索引缺 currentVersion 时不误报更新`() {
        assertEquals(
            PluginVersionAction.Download,
            pluginVersionAction(installedVersion = "3.1.0", hasUpdate = true, currentVersion = "", version = "3.1.1")
        )
    }
}
