package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 远端备份条目（listBackups 返回的单条）。
 *
 * @param id        远端标识（WebDAV 路径 / S3 key 等），恢复与删除时原样回传给插件
 * @param name      展示名（如 "Xime配置-2026-09-06.zip"）
 * @param createdAt 创建时间（毫秒时间戳；远端不提供时为 0）
 * @param size      字节数（远端不提供时为 -1）
 */
data class RemoteBackupEntry(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
    val size: Long = -1
)

/**
 * pushBackup 的执行结果。
 *
 * @param ok      是否成功
 * @param id      成功时远端条目 id（插件未返回时为 null，宿主随后 listBackups 获取）
 * @param message 失败原因（成功时为 null）
 */
data class BackupResult(
    val ok: Boolean,
    val id: String? = null,
    val message: String? = null
)

/**
 * 下载产物：宿主缓存文件 + 其 blob 句柄。
 *
 * @param blobId 宿主 [com.kingzcheung.xime.plugin.core.js.http.BlobStore] 句柄
 *   （[BackupPlugin.releaseBackup] 用；插件侧返回的就是它）
 * @param file   落盘的备份包（恢复流程直接解压，**不经过 JS 堆**）
 * @param size   字节数
 */
data class BackupDownload(
    val blobId: String,
    val file: java.io.File,
    val size: Long
)

/**
 * 备份插件能力接口（宿主侧，由 JS 适配器实现，协议逻辑在 JS）。
 *
 * 分工与 [ClipboardSyncPlugin] 一致：备份包的**生成与恢复**（zip 打包、路径校验、
 * 落盘）全部由宿主 BackupManager 承载，插件只负责**传输协议**
 * （WebDAV / S3 / 自建 HTTP），用 `host.http` + `host.crypto` + `host.config` 实现。
 * 服务器地址、账号等配置由插件 getSettingsSchema 表单承载（host.config 存取）。
 *
 * **大包走流式句柄**（v3.1 契约，替代历史 `Uint8Array` 传参）：
 * - 上传：归档以宿主文件形式交给 [pushBackup]，适配器登记为 blob 句柄后只把
 *   `{name, size, archiveId}` 传给插件；插件用 `host.http.upload` 流式 PUT，
 *   全程不把包读进内存（旧实现把整包 base64 进 JS 源码，数十 MB 即 OOM）
 * - 下载：插件用 `host.http.download` 让宿主把响应体流式落盘并返回 blob 句柄；
 *   适配器解析为 [BackupDownload] 供宿主解压，恢复完由 [releaseBackup] 释放
 */
interface BackupPlugin : IPluginEntryClass, IPluginConfigurable {

    /**
     * 上传备份包到远端。
     *
     * @param name    建议的远端文件名（如 "Xime配置-2026-09-06.zip"），插件可自行附加目录前缀
     * @param archive 宿主已落盘的 zip（**所有权归调用方**，实现方只登记句柄、不删除文件）
     */
    suspend fun pushBackup(name: String, archive: java.io.File): BackupResult

    /**
     * 下载指定备份包到宿主缓存文件。
     *
     * @param id listBackups 返回的远端标识
     * @return 落盘产物（含 blob 句柄）；失败返回 null
     */
    suspend fun pullBackup(id: String): BackupDownload?

    /** 释放 [pullBackup] 产物的 blob 句柄（删除宿主缓存文件；幂等）。 */
    fun releaseBackup(blobId: String)

    /**
     * 列出远端备份条目。
     *
     * @return 按创建时间倒序；失败返回 null（与"远端为空"的空列表区分）
     */
    suspend fun listBackups(): List<RemoteBackupEntry>?

    /** 删除远端备份。 */
    suspend fun deleteBackup(id: String): Boolean

    /** 校验配置可用性（连接测试），返回错误消息（null 表示成功）。 */
    suspend fun testConnection(): String?
}
