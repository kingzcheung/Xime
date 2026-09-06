package com.kingzcheung.xime.plugin.core.lua.sdk

/**
 * Lua 插件契约：入口脚本（main.lua）导出函数与数据格式的约定。
 *
 * ## 入口脚本
 * 插件包根目录的 main.lua（manifest.yaml 的 `entry` 字段指定）必须 `return` 一个
 * **导出表（table）**，宿主读取表中函数并按约定调用。
 *
 * ## 生命周期（可选）
 * - `onLoad()` 插件加载时调用（宿主已注入 host API）
 * - `onUnload()` 插件卸载时调用
 *
 * ## 分类能力（按 manifest.type 约定）
 * ### emoji 表情
 * - `getCategories()` -> string[]
 * - `getEmojis(query)` -> EmojiItem[]
 *   `query`: { keyword?: string, topK?: int }
 *   每项: { id: string, text: string, insertText?: string, imageUrl?: string }
 *   - text 同时作为显示文本与插入文本（可另给 insertText 区分上屏内容）
 *   - imageUrl 可通过 host.resource.path() 获得（图片渲染由宿主完成）
 *
 * ### tool 工具
 * - `getPanelState(inputText)` -> { inputText?, items: [], loading?, ui? }
 * - `onPanelInput(text)` 面板输入变化通知
 * - `onPanelAction(actionId)` 面板操作事件（宿主保留 `generate`）
 * - `onPanelItemClick(itemId)` 点候选上屏
 * - `ui`（可选，display: passive 时渲染）：统一声明式 UI 节点数组（UiNode 模型），
 *   节点 = `{ type, key?, label?, value?, defaultValue?, options?, placeholder?, helpText?,
 *   unit?, style?, section?, required? }`。
 *   面板展示白名单 type：`section(label)` / `text(value, style?)` / `metric(label, value, unit?)` /
 *   `divider` / `button(key, label)`；未知 type 降级为文本；节点数上限 64。
 *   旧字段名（title/content/actionId、type=action）解析层兼容（v0.2.0 存量插件）。
 *
 * ### 配置表单（getSettingsSchema，统一 UiNode 模型）
 * - `getSettingsSchema()` -> UiNode[]；表单字段 type：
 *   `text` / `textarea` / `secret` / `select` / `multi_select` / `switch` / `number` / `button`
 *   - 字段必须带 `key`（configStore 绑定）；`options` 为空时宿主经 `getOptions(key)` 动态拉取
 *   - `button` 点击 → 宿主调用插件导出函数（key 即函数名），nil/空返回 = 成功
 * - `getOptions(key)` 动态选项（如 ASR 模型列表）
 * - `start()` / `pushPcm()` / `stop()` 音频流式识别（网络 API 由宿主白名单提供）
 *
 * ### 事件（可选，manifest 声明 `capabilities.events` 才投递）
 * - `onPluginEvent(eventType, payload)` 宿主下行事件回调
 *   - 事件类型与 payload 字段名均为 snake_case
 *   - `input_changed`: payload = { input_text: string }，用户正在输入的编码快照
 *   - `text_committed`: payload = { committed_text: string, session_total_chars: int,
 *     session_total_commits: int }，文本上屏；累计值为宿主进程生命周期计数，
 *     conflated 丢中间事件不影响统计（插件用差值做增量持久化）
 *   - 通道为"只保最新"（conflated）：消费慢时中间事件被合并丢弃
 *   - 敏感输入框（密码类）不产生上述任何事件
 *   - 函数未导出时事件被静默丢弃，不影响其他能力
 *
 * ### 候选词变换（可选，manifest 声明 `capabilities.candidate_transform: true` 才调用）
 * - `transformCandidates(request)` -> { candidates: item[] } 或 nil（不干预）
 *   `request`: { input_text: string, preedit: string, ascii_mode: bool,
 *                candidates: { { text: string, comment: string }, ... } }
 *   每项 item 二选一：
 *   - { engine_index: int }  引用引擎候选（0 基，越界/重复被丢弃），comment 可选覆盖显示注释
 *   - { text: string, comment?: string } 插件候选，用户点击后直接上屏 text
 *   - 响应候选总数上限 20（超限截断）；返回 nil / 超时 / 报错 / 格式错误 → 宿主回退原始候选
 *   - 调用发生在按键路径（key-processing 线程，硬超时 15ms）：实现必须纯内存计算，
 *     禁止网络/文件 IO，连续超时 3 次本会话禁用
 *
 * ### 快捷发送只读（可选，manifest 声明 `capabilities.quick_send_read: true` 才注入）
 * - `host.quickSend.list()` -> { { id, text, code, timestamp, isPinned }, ... }
 *   快捷发送条目（宿主 Room 的 isQuickSend 子集，内存缓存同步读取，timestamp 降序，
 *   宿主上限 20 条）。`code` 为触发编码（如 dh，空 = 不参与编码匹配）。
 *   列表变更时宿主投递 `quick_send_changed` 事件（payload = { count }），
 *   插件收到后重新拉取；未声明该能力的插件 `host.quickSend` 为 nil。
 *
 * ### 剪贴板只读（可选，manifest 声明 `capabilities.clipboard_read: true` 才注入）
 * - `host.clipboard.get()` -> string 或 nil（当前剪贴板文本；非文本/空/无权限为 nil）
 *   未声明该能力的插件 `host.clipboard` 为 nil。
 *
 * ### backup 备份（manifest.type = backup，capabilities.backup 声明协议）
 * 宿主负责备份包的生成（zip）与恢复（校验落盘），插件只承载传输协议，
 * 用 `host.http` + `host.crypto` 实现，服务器配置由 getSettingsSchema 表单承载：
 * - `pushBackup(args)` -> { ok: bool, id?: string, message?: string } 或 bool
 *   `args`: { name: string（建议的远端文件名）, archive: string（zip 二进制字节流） }
 * - `pullBackup(id)` -> string（zip 二进制字节流）或 nil（失败）
 * - `listBackups()` -> { { id: string, name: string, createdAt?: int, size?: int }, ... } 或 nil
 *   按 createdAt 倒序返回；id 为远端标识（路径/key），恢复与删除时原样回传
 * - `deleteBackup(id)` -> bool
 * - `testConnection()` -> string（错误消息）或 nil（成功）
 *
 * ## 数据格式
 * Lua 返回值一律使用 Lua table（数组或 map），宿主统一做 table -> Kotlin 转换；
 * 函数不存在或抛错时，宿主返回空结果（不崩溃）。
 *
 * ## 与元数据的分工（v0.2.0 起）
 * 静态能力一律由 manifest.capabilities 声明（宿主唯一来源）：
 * - 布局（columns/itemHeightDp）→ capabilities.emoji
 * - 结果显示（display: direct/select）→ capabilities.tool
 * - ASR 能力（inputMode 等）→ capabilities.speech
 * Lua 侧不再导出 getCategoryLayoutConfig / getCapabilities /
 * getProviderId / getDisplayName / getState 等元信息函数。
 */
object LuaPluginContract {

    /** SDK 版本（宿主注入的 host.sdkVersion）。插件 manifest 可声明 `sdkVersion` 声明所需 SDK 版本。 */
    const val SDK_VERSION = "0.3.0"

    // ---- 宿主注入的全局对象 ----
    const val GLOBAL_HOST = "host"

    // ---- 生命周期 ----
    const val FN_ON_LOAD = "onLoad"
    const val FN_ON_UNLOAD = "onUnload"

    // ---- 事件（可选：manifest capabilities.events 声明后才投递） ----
    const val FN_ON_PLUGIN_EVENT = "onPluginEvent"

    // ---- 候选词变换（可选：manifest capabilities.candidate_transform 声明后才调用） ----
    const val FN_TRANSFORM_CANDIDATES = "transformCandidates"

    // ---- emoji ----
    const val FN_GET_CATEGORIES = "getCategories"
    const val FN_GET_EMOJIS = "getEmojis"

    // ---- tool ----
    const val FN_GET_PANEL_STATE = "getPanelState"
    const val FN_ON_PANEL_INPUT = "onPanelInput"
    const val FN_ON_PANEL_ACTION = "onPanelAction"
    const val FN_ON_PANEL_ITEM_CLICK = "onPanelItemClick"

    // ---- backup（manifest capabilities.backup 声明协议后由备份设置页调用） ----
    const val FN_PUSH_BACKUP = "pushBackup"
    const val FN_PULL_BACKUP = "pullBackup"
    const val FN_LIST_BACKUPS = "listBackups"
    const val FN_DELETE_BACKUP = "deleteBackup"

    // ---- emoji item 字段 ----
    const val FIELD_ID = "id"
    const val FIELD_TEXT = "text"
    const val FIELD_INSERT_TEXT = "insertText"
    const val FIELD_IMAGE_URL = "imageUrl"
}