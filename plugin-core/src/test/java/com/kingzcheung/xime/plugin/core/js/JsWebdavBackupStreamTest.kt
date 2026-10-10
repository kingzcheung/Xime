package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 简易 blob 仓库：owner 校验 + owned 释放删除（与 app 层 PluginBlobStore 同语义）。 */
private class TestBlobStore(rootDir: File) : BlobStore {
    private data class Entry(val pluginId: String, val file: File, val owned: Boolean)

    private val root = rootDir.apply { mkdirs() }
    private val entries = LinkedHashMap<String, Entry>()
    private var seq = 0

    override fun createTempFile(ownerPluginId: String): File = File(root, "tmp-${seq++}.bin")

    override fun register(ownerPluginId: String, file: File, owned: Boolean): String {
        val id = "blob-${seq++}"
        entries[id] = Entry(ownerPluginId, file, owned)
        return id
    }

    override fun resolve(pluginId: String, id: String): File? {
        val entry = entries[id] ?: return null
        return if (entry.pluginId == pluginId && entry.file.isFile) entry.file else null
    }

    override fun release(id: String) {
        val entry = entries.remove(id) ?: return
        if (entry.owned) entry.file.delete()
    }

    fun isRegistered(id: String): Boolean = entries.containsKey(id)
}

/**
 * 备份包流式传输端到端（B 期核心回归）。
 *
 * 载入真实插件产物（xipm build 输出），验证 v3.1 契约：
 * - backup.push 只拿 `{name, size, archiveId}`，整包经 host.http.upload 由宿主发送
 *   （插件侧从不接触包体）
 * - PUT 409 → MKCOL → 重试仍可复用同一句柄（流式句柄不能被"读一次就废"）
 * - backup.pull 返回 host.http.download 落盘的 blob 句柄，适配器解析成宿主文件
 * - 句柄用后释放（不泄漏登记项 / 不泄漏缓存文件）
 */
class JsWebdavBackupStreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class FakeCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(20)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String = java.util.Base64.getEncoder().encodeToString(data)
        override fun utcTime(format: String): String = ""
        override fun epochSeconds(): Long = 1767225600
    }

    private class DebugHostApi(private val store: PluginConfigStore) : JsHostApi {
        override val sdkVersion = "3.1.0"
        override fun log(message: String) {}
        override fun logError(message: String) {}
        override fun configGet(key: String): String? = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys(): Set<String> = store.keys()
        override fun resourcePath(name: String): String? = null
        override fun resourceList(dir: String): List<String> = emptyList()
        override fun uuid() = "uuid"
    }

    /** 记录流式上传的假宿主：可模拟 PUT 409 → MKCOL → 重试；下载落盘为 blob。 */
    private class FakeStreamHttp(private val store: TestBlobStore) : HttpHostApi {
        val uploads = mutableListOf<Triple<String, String, String>>()

        /** 上传瞬间句柄解析到的文件（证明"插件给句柄、宿主读文件"） */
        var resolvedAtUpload: File? = null
        var mkcolCount = 0
        var failFirstPut = false
        var downloadBytes = ByteArray(0)
        var downloadStatus = 200
        var returnUnknownBlob = false

        override fun request(
            method: String, url: String, headers: Map<String, String>,
            body: ByteArray?, timeoutMillis: Int?
        ): HttpResponse? = when (method) {
            "MKCOL" -> { mkcolCount++; HttpResponse(201) }
            "PROPFIND" -> HttpResponse(207, emptyMap(), "<multistatus/>".toByteArray())
            else -> HttpResponse(404)
        }

        override fun upload(
            method: String, url: String, headers: Map<String, String>,
            blobId: String, timeoutMillis: Int?
        ): HttpResponse? {
            uploads += Triple(method, url, blobId)
            resolvedAtUpload = store.resolve(PLUGIN_ID, blobId)
            if (resolvedAtUpload == null) return null // 句柄失效：宿主侧同语义（upload 返回 null）
            // 第一次 PUT 报 409（父目录不存在）：重试必须还能解析同一个句柄
            if (failFirstPut && uploads.size == 1) return HttpResponse(409)
            return HttpResponse(201, mapOf("ETag" to "\"e1\""), ByteArray(0))
        }

        override fun download(
            method: String, url: String, headers: Map<String, String>, timeoutMillis: Int?
        ): HttpResponse? {
            if (downloadStatus != 200) {
                return HttpResponse(downloadStatus, emptyMap(), "错误页".toByteArray())
            }
            if (returnUnknownBlob) {
                return HttpResponse(200, emptyMap(), ByteArray(0), blobId = "unknown-blob", size = 1)
            }
            val file = store.createTempFile(PLUGIN_ID).apply { writeBytes(downloadBytes) }
            val id = store.register(PLUGIN_ID, file, owned = true)
            return HttpResponse(200, emptyMap(), ByteArray(0), blobId = id, size = downloadBytes.size.toLong())
        }

        override fun lastError(): String? = "fake 网络失败"
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/webdav-backup/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/webdav-backup/main.js，" +
                "请先运行：xipm build plugins/webdav-backup --out build/plugin-js"
        )
    }

    /**
     * 载入真实插件产物并接线适配器。
     *
     * @param streamingEnabled false = 模拟宿主未提供句柄仓库（适配器应显式失败而非崩溃）
     */
    private fun loadPlugin(
        streamingEnabled: Boolean = true
    ): Triple<JsBackupPluginAdapter, FakeStreamHttp, TestBlobStore> {
        val blobStore = TestBlobStore(File(tmp.root, "blobs"))
        val dir = tmp.newFolder("webdav-backup")
        pluginSourceFile().copyTo(File(dir, "main.js"))
        val config = InMemoryConfigStore().apply {
            set("url", "https://dav.jianguoyun.com/dav/")
            set("username", "user")
            set("password", "pass")
            set("remote_path", "/xime_backup")
        }
        val http = FakeStreamHttp(blobStore)
        val runtime = JsScriptRuntime(
            "js-webdav-backup-stream",
            dir,
            "main.js",
            config,
            hostApi = DebugHostApi(config),
            httpHostApi = http,
            cryptoHostApi = FakeCryptoHostApi()
        )
        assertTrue("main.js 应能加载", runtime.load())
        val info = PluginInfo(
            id = PLUGIN_ID, name = "WebDAV 备份", iconResId = 0, versionCode = 1,
            versionName = "3.0.0", path = File(dir, "main.js").absolutePath,
            description = "测试", type = "backup"
        )
        val adapter = JsBackupPluginAdapter(
            runtime,
            PluginContext(application = Application(), pluginInfo = info, configStore = config),
            blobStore = if (streamingEnabled) blobStore else null
        )
        return Triple(adapter, http, blobStore)
    }

    @Test(timeout = 60_000)
    fun `push 走宿主 blob 句柄且整包不进 JS`() {
        val (adapter, http, blobStore) = loadPlugin()
        val archive = File(tmp.root, "Xime配置-2026-10-09.zip").apply {
            writeBytes(ByteArray(4 * 1024 * 1024) { (it % 251).toByte() })
        }
        val size = archive.length()
        try {
            val result = runBlocking { adapter.pushBackup("Xime配置-2026-10-09.zip", archive) }

            assertTrue("上传应成功: ${result.message}", result.ok)
            assertEquals("条目 id 为远端绝对路径", "/dav/xime_backup/Xime配置-2026-10-09.zip", result.id)
            assertEquals("应只有一次 PUT", 1, http.uploads.size)
            assertEquals("PUT", http.uploads[0].first)
            // 插件拿到的是不透明句柄，不是路径；宿主可解析回原文件
            val blobId = http.uploads[0].third
            assertFalse("句柄不得泄露文件路径", blobId.contains("Xime配置"))
            assertEquals("上传瞬间句柄应解析到宿主落盘的备份包", archive, http.resolvedAtUpload)
            assertEquals("包体大小应与文件一致", size, archive.length())
            // 上传结束后句柄释放（owned=false → 不删文件，所有权仍在调用方）
            assertFalse("句柄应已注销（不泄漏登记项）", blobStore.isRegistered(blobId))
            assertTrue("调用方文件不应被删除", archive.isFile)
        } finally {
            adapter.onUnload()
        }
    }

    @Test(timeout = 60_000)
    fun `PUT 409 时逐级 MKCOL 后重试同一句柄`() {
        val (adapter, http, blobStore) = loadPlugin()
        http.failFirstPut = true
        val archive = File(tmp.root, "retry.zip").apply { writeBytes(ByteArray(1024) { 7 }) }
        try {
            val result = runBlocking { adapter.pushBackup("retry.zip", archive) }

            assertTrue("重试后应成功: ${result.message}", result.ok)
            assertEquals("应重试一次 PUT", 2, http.uploads.size)
            assertEquals("重试必须复用同一 blob 句柄", http.uploads[0].third, http.uploads[1].third)
            assertTrue("父目录应被逐级创建", http.mkcolCount >= 1)
            assertFalse("重试后句柄同样释放", blobStore.isRegistered(http.uploads[0].third))
        } finally {
            adapter.onUnload()
        }
    }

    @Test(timeout = 60_000)
    fun `pull 返回落盘文件句柄并可释放`() {
        val (adapter, http, blobStore) = loadPlugin()
        http.downloadBytes = "PK\u0003\u0004 伪备份包内容".toByteArray(Charsets.UTF_8)
        try {
            val download = runBlocking { adapter.pullBackup("/dav/xime_backup/Xime.zip") }

            assertTrue("应返回落盘产物", download != null)
            assertEquals("落盘内容应与响应体一致", "PK\u0003\u0004 伪备份包内容", download!!.file.readText())
            assertEquals(download.file.length(), download.size)
            assertTrue("句柄应处于登记状态（供恢复流程消费）", blobStore.isRegistered(download.blobId))

            adapter.releaseBackup(download.blobId)

            assertFalse("释放应注销句柄", blobStore.isRegistered(download.blobId))
            assertFalse("释放应删除宿主缓存文件", download.file.exists())
        } finally {
            adapter.onUnload()
        }
    }

    @Test(timeout = 60_000)
    fun `pull 失败或句柄未知时返回 null`() {
        val (adapter, http, _) = loadPlugin()
        try {
            http.downloadStatus = 401
            assertNull("非 200 应返回 null", runBlocking { adapter.pullBackup("/dav/xime_backup/Xime.zip") })

            http.downloadStatus = 200
            http.returnUnknownBlob = true
            assertNull("宿主解析不到的句柄应返回 null", runBlocking { adapter.pullBackup("/dav/xime_backup/Xime.zip") })
        } finally {
            adapter.onUnload()
        }
    }

    @Test(timeout = 60_000)
    fun `宿主未提供 blobStore 时 push 明确失败而不是崩溃`() {
        val (adapter, _, _) = loadPlugin(streamingEnabled = false)
        val archive = File(tmp.root, "no-store.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val result = runBlocking { adapter.pushBackup("no-store.zip", archive) }

            assertFalse(result.ok)
            assertEquals("宿主未提供流式传输能力", result.message)
        } finally {
            adapter.onUnload()
        }
    }

    private companion object {
        const val PLUGIN_ID = "com.kingzcheung.xime.plugin.webdav_backup"
    }
}
