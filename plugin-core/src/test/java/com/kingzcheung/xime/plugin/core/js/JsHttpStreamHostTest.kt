package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `host.http.upload` / `host.http.download` 桥接（大文件流式原语，协议无关）。
 *
 * 关键契约：
 * - upload 把 blobId 原样交给宿主实现（插件不接触文件），返回常规响应（状态/头/正文）
 * - download 2xx 时正文由宿主落盘，JS 侧拿到 blobId + size（body 为空）
 * - 宿主实现不支持 / blob 失效 → reject XimeError（含 code）
 */
class JsHttpStreamHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val pluginJs = """
        globalThis.plugin = {
          probe: async function () {
            var up = await host.http.upload('PUT', 'https://dav.example.com/x.zip',
              { 'Content-Type': 'application/octet-stream' }, 'blob-1');
            var down = await host.http.download('GET', 'https://dav.example.com/x.zip', {});
            return {
              upStatus: up.status, upText: up.text, upSize: up.size,
              upBlob: (up.blobId === null || up.blobId === undefined) ? 'none' : 'set',
              downStatus: down.status, downBlob: down.blobId, downSize: down.size,
              downBodyLen: down.body.length, downText: down.text
            };
          },
          probeFail: async function () {
            try {
              await host.http.upload('PUT', 'https://dav.example.com/x.zip', {}, 'missing');
              return 'no-error';
            } catch (e) {
              return e.code + ':' + e.message;
            }
          }
        };
    """.trimIndent()

    /** 支持流式的宿主实现：upload 记录 blobId 后回 201；download 回落盘句柄。 */
    private class StreamingHttpApi : HttpHostApi {
        val uploads = mutableListOf<Triple<String, String, String>>()
        override fun request(
            method: String, url: String, headers: Map<String, String>,
            body: ByteArray?, timeoutMillis: Int?
        ): HttpResponse? = HttpResponse(200, emptyMap(), "req".toByteArray())

        override fun upload(
            method: String, url: String, headers: Map<String, String>,
            blobId: String, timeoutMillis: Int?
        ): HttpResponse? {
            if (blobId == "missing") return null
            uploads += Triple(method, url, blobId)
            return HttpResponse(201, mapOf("ETag" to "\"v1\""), "created".toByteArray())
        }

        override fun download(
            method: String, url: String, headers: Map<String, String>, timeoutMillis: Int?
        ): HttpResponse? = HttpResponse(
            status = 200, headers = mapOf("Content-Length" to "1234"),
            body = ByteArray(0), blobId = "down-1", size = 1234
        )

        override fun lastError(): String? = "blob 不存在或句柄已失效"
    }

    /** 只实现内存 request 的旧实现：流式方法走接口默认实现（null）→ E_NETWORK。 */
    private class LegacyHttpApi : HttpHostApi {
        override fun request(
            method: String, url: String, headers: Map<String, String>,
            body: ByteArray?, timeoutMillis: Int?
        ): HttpResponse? = HttpResponse(200)

        override fun lastError(): String? = "宿主未提供流式上传"
    }

    private fun makeRuntime(api: HttpHostApi): JsScriptRuntime {
        val dir = tmp.newFolder("http-stream-probe")
        File(dir, "main.js").writeText(pluginJs)
        return JsScriptRuntime(
            pluginId = "http-stream-probe",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
            httpHostApi = api
        )
    }

    @Test(timeout = 60_000)
    fun `upload 把 blobId 交给宿主实现并返回常规响应`() {
        val api = StreamingHttpApi()
        val runtime = makeRuntime(api)
        try {
            assertTrue(runtime.load())
            val out = runtime.callAsync("probe") as Map<*, *>

            assertEquals("PUT", api.uploads[0].first)
            assertEquals("https://dav.example.com/x.zip", api.uploads[0].second)
            assertEquals("插件只传句柄，宿主解析文件", "blob-1", api.uploads[0].third)

            assertEquals(201, (out["upStatus"] as Number).toInt())
            assertEquals("created", out["upText"]?.toString())
            assertEquals("上传响应仍走内存正文", "none", out["upBlob"]?.toString())
            assertEquals(-1, (out["upSize"] as Number).toInt())
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `download 返回落盘句柄且正文不占 JS 堆`() {
        val runtime = makeRuntime(StreamingHttpApi())
        try {
            assertTrue(runtime.load())
            val out = runtime.callAsync("probe") as Map<*, *>

            assertEquals(200, (out["downStatus"] as Number).toInt())
            assertEquals("down-1", out["downBlob"]?.toString())
            assertEquals(1234, (out["downSize"] as Number).toInt())
            assertEquals("2xx 正文已落盘，JS 侧 body 为空", 0, (out["downBodyLen"] as Number).toInt())
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `宿主拒绝流式上传时 reject XimeError 携带原因`() {
        val runtime = makeRuntime(StreamingHttpApi())
        try {
            assertTrue(runtime.load())
            assertEquals(
                "E_NETWORK:blob 不存在或句柄已失效",
                runtime.callAsync("probeFail")
            )
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `旧宿主实现未覆盖流式方法时优雅失败而非崩溃`() {
        val runtime = makeRuntime(LegacyHttpApi())
        try {
            assertTrue(runtime.load())
            assertEquals(
                "E_NETWORK:宿主未提供流式上传",
                runtime.callAsync("probeFail")
            )
        } finally {
            runtime.close()
        }
    }
}
