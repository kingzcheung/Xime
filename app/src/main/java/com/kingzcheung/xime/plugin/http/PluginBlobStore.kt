package com.kingzcheung.xime.plugin.http

import android.util.Log
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [BlobStore] 的 app 层实现：临时文件落在 cacheDir/plugin-blobs，句柄为随机 UUID。
 *
 * - **id 不可猜**：UUID 随机串，且 [resolve] 校验登记方 pluginId，插件之间互不可见；
 * - **所有权**：`owned=true`（流式下载产物）在 [release] 时删除；`owned=false`
 *   （BackupManager 落盘的备份包）只注销，文件由原调用方清理；
 * - **进程残留**：init 时清理超过 [STALE_MILLIS] 的历史暂存文件（崩溃/被杀不会堆积），
 *   并限制单次下载体积上限（防恶意/异常服务器写满磁盘）。
 */
class PluginBlobStore(private val rootDir: File) : BlobStore {

    companion object {
        private const val TAG = "PluginBlobStore"

        /** 残留暂存文件保留时长（超过即视为无人引用的垃圾）。 */
        private const val STALE_MILLIS = 24L * 60 * 60 * 1000

        /** 单次流式下载落盘上限（512MB，防写满磁盘）。 */
        const val MAX_BLOB_BYTES = 512L * 1024 * 1024
    }

    private data class Entry(val pluginId: String, val file: File, val owned: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        rootDir.mkdirs()
        sweepStale()
    }

    override fun createTempFile(ownerPluginId: String): File {
        rootDir.mkdirs()
        return File(rootDir, "blob-${UUID.randomUUID()}.tmp")
    }

    override fun register(ownerPluginId: String, file: File, owned: Boolean): String {
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(ownerPluginId, file, owned)
        return id
    }

    override fun resolve(pluginId: String, id: String): File? {
        val entry = entries[id] ?: return null
        if (entry.pluginId != pluginId) {
            Log.w(TAG, "blob 句柄跨插件引用被拒绝: $id")
            return null
        }
        if (!entry.file.isFile) {
            entries.remove(id)
            return null
        }
        return entry.file
    }

    override fun release(id: String) {
        val entry = entries.remove(id) ?: return
        if (entry.owned && !entry.file.delete()) {
            Log.w(TAG, "blob 暂存文件删除失败: ${entry.file.name}")
        }
    }

    /** 清理 init 之前的残留暂存文件（进程被杀/崩溃留下的 blob 不该长期占用 cache）。 */
    private fun sweepStale() {
        val deadline = System.currentTimeMillis() - STALE_MILLIS
        val stale = rootDir.listFiles()?.filter { it.isFile && it.lastModified() < deadline } ?: return
        var removed = 0
        for (file in stale) {
            if (file.delete()) removed++
        }
        if (removed > 0) Log.i(TAG, "清理残留 blob 暂存文件 $removed 个")
    }
}
