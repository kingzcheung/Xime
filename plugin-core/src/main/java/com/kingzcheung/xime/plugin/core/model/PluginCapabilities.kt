package com.kingzcheung.xime.plugin.core.model

import com.kingzcheung.xime.plugin.core.api.ToolResult

/**
 * 插件能力声明（manifest.capabilities）：宿主消费能力的**唯一来源**。
 *
 * 静态能力（搜索支持、布局、结果模式、录音格式等）一律由元数据声明，
 * 插件 Lua 侧只提供运行时数据与事件处理，不再重复声明。
 */
data class PluginCapabilities(
    val emoji: EmojiCapabilities? = null,
    val speech: SpeechCapabilities? = null,
    val tool: ToolCapabilities? = null,
    @kotlinx.serialization.SerialName("clipboard_sync")
    val clipboardSync: ClipboardSyncCapabilities? = null,
    @kotlinx.serialization.SerialName("backup")
    val backup: BackupCapabilities? = null,
    /** 下行事件订阅声明（如 "input_changed"）：未声明的事件宿主不投递，通道也不建立。 */
    val events: List<String> = emptyList(),
    /** 候选词变换能力（manifest 声明 `candidate_transform: true`）：rime 返回候选后、
     *  候选栏渲染前，宿主同步调用插件 transformCandidates 修改候选；首个接入输入
     *  主流程（hotPath）的能力，硬超时 15ms + 敏感输入短路 + 连续超时熔断。 */
    @kotlinx.serialization.SerialName("candidate_transform")
    val candidateTransform: Boolean = false,
    /** 快捷发送只读能力（manifest 声明 `quick_send_read: true`）：声明后宿主注入
     *  `host.quickSend`（list()），并允许订阅 `quick_send_changed` 事件。 */
    @kotlinx.serialization.SerialName("quick_send_read")
    val quickSendRead: Boolean = false,
    /** 剪贴板只读能力（manifest 声明 `clipboard_read: true`）：声明后宿主注入
     *  `host.clipboard`（getText()）。 */
    @kotlinx.serialization.SerialName("clipboard_read")
    val clipboardRead: Boolean = false,
) {
    companion object {
        val EMPTY = PluginCapabilities()
    }

    /** emoji 表情能力声明。 */
    data class EmojiCapabilities(
        val supportsSearch: Boolean = false,
        /** 网格列数（缺省按宿主默认）。 */
        val columns: Int? = null,
        /** 单行高度 dp。 */
        val itemHeightDp: Int? = null,
    )

    /** speech 语音识别能力声明。 */
    data class SpeechCapabilities(
        val inputMode: String = "streaming",
        val supportsPartialResults: Boolean = true,
        val requiresNetwork: Boolean = true,
    )

    /** tool 工具面板能力声明。 */
    data class ToolCapabilities(
        /** 结果显示方式：DIRECT 直接上屏 / SELECT 全屏候选页面；null 宿主按结果数量兜底。 */
        val display: ToolResult? = null,
    )

    /** clipboard_sync 剪贴板同步能力声明。 */
    data class ClipboardSyncCapabilities(
        val protocols: List<String> = emptyList(),
    )

    /** backup 备份能力声明：宿主负责备份包生成/恢复，插件只承载传输协议。 */
    data class BackupCapabilities(
        val protocols: List<String> = emptyList(),
    )
}