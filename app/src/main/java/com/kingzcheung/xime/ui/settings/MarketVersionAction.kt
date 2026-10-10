package com.kingzcheung.xime.ui.settings

/**
 * 插件"版本历史"里单个版本卡片的动作。
 *
 * 为什么需要它：`MarketPluginItem.installed` 是**插件级**状态（"这个插件装了"），
 * 不是"这一版装了"。旧实现直接用它决定每个版本卡片 → 只要插件装了，版本历史里
 * **每一版都显示「已安装」**（用户反馈："funasr 还存在多个版本已安装"）。
 * 实际安装是按插件 id 覆盖的（[com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager]
 * 会先删掉旧目录再解包），磁盘上只有一个版本。
 */
internal enum class PluginVersionAction {
    /** 本机当前装的就是这一版。 */
    Installed,

    /** 索引当前版本，且本机装的不是它（有更新可装）。 */
    Update,

    /** 其余历史版本：可主动下载安装（市场支持按版本安装/降级）。 */
    Download,
}

/**
 * 按"这一版 vs 本机已装版本"判定卡片动作。
 *
 * @param installedVersion 本机已装版本（未安装为 null）
 * @param hasUpdate 插件级"有更新"（= 已装 && 已装版本 != 索引 currentVersion）
 * @param currentVersion 索引里的当前版本
 * @param version 本卡片代表的版本
 */
internal fun pluginVersionAction(
    installedVersion: String?,
    hasUpdate: Boolean,
    currentVersion: String,
    version: String,
): PluginVersionAction = when {
    installedVersion != null && version == installedVersion -> PluginVersionAction.Installed
    hasUpdate && currentVersion.isNotBlank() && version == currentVersion -> PluginVersionAction.Update
    else -> PluginVersionAction.Download
}
