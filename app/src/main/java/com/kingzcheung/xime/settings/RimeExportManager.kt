package com.kingzcheung.xime.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包范围（**只有一个范围：完整备份**）。
 *
 * 这个应用里"备份"就一件事——把整机状态打成一个时间点包：
 * - rime 目录里的**设置与用户补丁**（`*.custom.yaml`、`xime.custom.yaml`、`user.yaml`、
 *   `installation.yaml`、`custom_phrase.txt`、`symbols.yaml`）
 * - **方案本体与资源**：`*.schema.yaml` / `*.dict.yaml` / `opencc/` / `lua/` / `fonts/` / `themes/`
 * - **自造词快照**：`sync/<installation_id>/` 下的 `*.userdb.txt`（恢复时按时间戳合并）
 * - 插件包与插件/方案清单、设置项与插件配置（见 [BackupManager.collectMetaEntries]）
 *
 * 一律排除"派生/可由引擎重建/另有合并通道"的东西（见 [includeRimeEntry]）：
 * `build/`（librime 编译产物）、`*.bin/.gram/.db*`（二进制词典与模型）、
 * `*.userdb/`（leveldb 用户词典库）、`default.yaml`（派生基座，恢复后重新镜像 schema_list）、
 * 以及其他设备的 `sync/<其它 id>/`。
 *
 * 自造词另有专门的**词条同步**通道（增量、多设备按时间戳合并）；本包里的快照是"顺带一份"，
 * 恢复时同样走合并，不会覆盖本机更新的词条。
 */
data class ExportResult(
    val uri: Uri?,
    val fileName: String,
    val savedToDownloads: Boolean
)

object RimeExportManager {

    private const val TAG = "RimeExportManager"

    fun shareSingleFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = inferMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        val chooser = Intent.createChooser(intent, "分享文件")
        context.startActivity(chooser)
    }

    fun exportArchive(context: Context): Result<ExportResult> {
        try {
            val (fileName, tempZip) = buildArchiveToFile(context).getOrElse { return Result.failure(it) }
            val savedToDownloads = try {
                saveToDownloads(context, tempZip, fileName)
            } finally {
                tempZip.delete()
            }

            val resultUri = if (savedToDownloads) {
                resolveDownloadsUri(context, fileName)
            } else {
                null
            }

            // 生成了完整备份包即视为一次备份（本地导出与云备份共用此口径）
            SettingsPreferences.setLastBackupAt(context, System.currentTimeMillis())
            return Result.success(ExportResult(resultUri, fileName, savedToDownloads))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "exportArchive failed", e)
            return Result.failure(e)
        }
    }

    /**
     * 流式生成备份包到 cacheDir（**不驻留内存**），供本地导出与云备份（BackupManager）共用。
     *
     * 包内容（v1 格式，`_xime_backup/` 前缀外均为 rime 目录相对路径；范围见 [includeRimeEntry]）：
     * 设置/补丁 + 方案本体与资源 + 自造词快照 + 插件包 + 清单（元数据见 [BackupManager.collectMetaEntries]）
     *
     * 逐条目边读边压（ZipOutputStream → FileOutputStream），内存占用与包体无关；
     * 完整方案含方案词典与插件包时可达数十 MB，旧的整体 ByteArray 实现会因此 OOM。
     *
     * @return Pair(文件名, 落盘文件)；**调用方负责删除**该文件
     */
    fun buildArchiveToFile(context: Context): Result<Pair<String, File>> {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val fileName = "Xime完整备份-$dateStr.zip"
        val cacheDir = File(context.cacheDir, "backup-export").apply { mkdirs() }
        val archive = File(cacheDir, fileName)
        return try {
            val rimeDir = File(context.filesDir, "rime")
            if (!rimeDir.exists()) {
                return Result.failure(Exception("Rime 目录不存在"))
            }
            val installationId = SettingsPreferences.getRimeInstallationId(context)

            FileOutputStream(archive).use { fileOut ->
                ZipOutputStream(BufferedOutputStream(fileOut)).use { zos ->
                    rimeDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) return@forEach
                        val relativePath = file.relativeTo(rimeDir).path.replace('\\', '/')
                        if (!includeRimeEntry(relativePath, installationId)) return@forEach
                        zos.putNextEntry(ZipEntry(relativePath))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    BackupManager.collectMetaEntries(context).forEach { (entryName, bytes) ->
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                    val pluginsDir = File(context.filesDir, "plugins")
                    pluginsDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) return@forEach
                        val entryName = BackupManager.META_PREFIX + "plugins/" +
                            file.relativeTo(pluginsDir).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            if (archive.length() == 0L) {
                archive.delete()
                return Result.failure(Exception("没有可导出的文件"))
            }
            Result.success(fileName to archive)
        } catch (e: Exception) {
            archive.delete()
            android.util.Log.e(TAG, "buildArchiveToFile failed", e)
            Result.failure(e)
        }
    }

    /**
     * 保存任意 zip 文件到 Downloads（词库同步快照包由 SyncManager 打包后经此落盘），
     * 返回是否成功；**不删除**源文件（调用方自行清理）。
     */
    fun saveSyncArchive(context: Context, fileName: String, zipFile: File): Boolean {
        return try {
            saveToDownloads(context, zipFile, fileName)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveSyncArchive failed", e)
            false
        }
    }

    private fun saveToDownloads(context: Context, zipFile: File, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) return false
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    zipFile.inputStream().use { it.copyTo(output) }
                }
                true
            } else {
                @Suppress("DEPRECATION")
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, fileName)
                zipFile.copyTo(dest, overwrite = true)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveToDownloads failed", e)
            false
        }
    }

    private fun resolveDownloadsUri(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        return null
    }

    /**
     * 单条 rime 目录条目是否进包（纯函数，单测锚定）。
     *
     * 排除项（均属"派生/可由引擎重建"或"另有合并通道"）：
     * - `build/` 目录：librime 编译产物（本机实测占 rime 目录一半），跨版本不兼容
     * - `*.bin` / `*.gram` / `*.db*`：二进制词典与模型
     * - `*.userdb/` 目录：leveldb 用户词典库——文件级覆盖一致性差，且会吃掉其他设备新词；
     *   自造词改走 `sync/` 文本快照 + 引擎按时间戳合并（见 [SyncManager]）
     * - `default.yaml`：由 `default.custom.yaml` + APK 内置基座派生，恢复后再镜像
     *   schema_list（[SchemaManager.applyEnabledSchemasToDefaultYaml]），直接回灌会带回旧基座
     * - `sync/` 下**非本机 installation_id** 的目录：那是其它设备的历史快照
     *
     * @param installationId 本机 rime installation id（`sync/<id>/` 才认）
     */
    internal fun includeRimeEntry(
        relativePath: String,
        installationId: String
    ): Boolean {
        val path = relativePath.replace('\\', '/')
        // 派生与二进制
        if (path.startsWith("build/")) return false
        if (path == "default.yaml") return false
        if (path.endsWith(".bin") || path.endsWith(".gram")) return false
        if (path.endsWith(".db") || path.endsWith(".db-wal") || path.endsWith(".db-shm")) return false
        // leveldb 用户词典**目录**（pinyin_simp.userdb/…）：按路径段精确判断，
        // 否则会把自造词快照 pinyin_simp.userdb.txt 一起误伤
        if (path.split('/').any { it.endsWith(".userdb") }) return false
        // sync 目录：只收**本机**的自造词快照 `*.userdb.txt`。
        //
        // 注意：librime 的 sync_user_data 会把用户目录下所有顶层 .yaml/.txt 镜像一份到
        // sync/<user_id>/（lever/deployment_tasks.cc 的 BackupConfigFiles），词典/配置
        // 镜像动辄 20MB+——那是本包两档各自已覆盖的内容（配置在轻量档、方案在完整档），
        // 绝不能跟着快照一起塞进来（曾导致"设置与自造词"档实测 8.4MB）。
        // 其它 installation_id 的目录是别设备的历史状态，也不收。
        if (path == "sync" || path.startsWith("sync/")) {
            if (installationId.isEmpty()) return false
            if (path != "sync/$installationId" && !path.startsWith("sync/$installationId/")) return false
            return path.endsWith(".userdb.txt")
        }
        // 其余为设置/用户补丁与方案本体/资源（*.custom.yaml、xime.custom.yaml、user.yaml、
        // installation.yaml、custom_phrase.txt、symbols.yaml、方案 schema/dict、opencc/、lua/、fonts/、themes/）
        return true
    }

    private fun inferMimeType(file: File): String {
        return when {
            file.name.endsWith(".yaml") -> "text/vnd.yaml"
            file.name.endsWith(".txt") -> "text/plain"
            file.name.endsWith(".bin") -> "application/octet-stream"
            file.name.endsWith(".gram") -> "application/octet-stream"
            file.name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
