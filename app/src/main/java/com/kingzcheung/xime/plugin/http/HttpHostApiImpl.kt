package com.kingzcheung.xime.plugin.http

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginNetworkAuthHelper
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.settings.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 宿主通用 HTTP 白名单 API。
 *
 * - URL 域名须通过 [NetworkPolicy]：插件已声明且经用户授权，否则拒绝
 *   （插件无法静默发起任意网络请求，自定义服务器域名需用户在插件中心手动授权）
 * - 同步阻塞执行：必须在 IO 线程调用（宿主同步引擎在 Dispatchers.IO 运行）
 * - 协议无关：认证头/ETag/SigV4 全部由插件 Lua 组装，宿主只透传
 * - 大文件走 [upload] / [download]：请求体/响应体经 [BlobStore] 与磁盘块拷贝，
 *   内存占用与文件大小无关（备份包上传不再 OOM）
 */
class HttpHostApiImpl(
    private val context: Context,
    private val pluginId: String,
    private val blobStore: BlobStore
) : HttpHostApi {

    companion object {
        private const val TAG = "HttpHostApi"

        /** 流式拷贝缓冲（与 Okio 段大小一致，避免逐字节调用）。 */
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            Log.d(TAG, "[$pluginId] >>> ${request.method} ${request.url}")
            val start = System.currentTimeMillis()
            try {
                val resp = chain.proceed(request)
                Log.d(TAG, "[$pluginId] <<< ${resp.code} in ${System.currentTimeMillis() - start}ms")
                resp
            } catch (e: Exception) {
                Log.d(TAG, "[$pluginId] <<< FAIL ${e.message} in ${System.currentTimeMillis() - start}ms")
                throw e
            }
        }
        .build()

    @Volatile
    private var lastErrorMsg: String? = null

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMillis: Int?
    ): HttpResponse? {
        if (!authorize(url)) return null
        lastErrorMsg = null
        val requestBody = (body ?: ByteArray(0)).toRequestBody(contentTypeOf(headers).toMediaType())
        return execute(method, url, headers, requestBody, timeoutMillis) { toHttpResponse(it) }
    }

    /**
     * 流式上传：请求体直接来自宿主 blob 文件（Content-Length 由文件长度给出），
     * OkHttp 按 8KB 段写入网络，全程不把文件读进内存。
     */
    override fun upload(
        method: String,
        url: String,
        headers: Map<String, String>,
        blobId: String,
        timeoutMillis: Int?
    ): HttpResponse? {
        if (!authorize(url)) return null
        lastErrorMsg = null
        val file = blobStore.resolve(pluginId, blobId)
        if (file == null) {
            lastErrorMsg = "上传源不存在或句柄已失效"
            Log.w(TAG, "[$pluginId] blob 解析失败: $blobId")
            return null
        }
        val requestBody = FileRequestBody(file, contentTypeOf(headers).toMediaType())
        return execute(method, url, headers, requestBody, timeoutMillis) { toHttpResponse(it) }
    }

    /**
     * 流式下载：2xx 正文边收边写 cacheDir 暂存文件（上限 [PluginBlobStore.MAX_BLOB_BYTES]），
     * 登记为 blob 后返回句柄；非 2xx 仍按内存响应返回（错误页很小，便于插件展示原因）。
     */
    override fun download(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Int?
    ): HttpResponse? {
        if (!authorize(url)) return null
        lastErrorMsg = null
        return execute(method, url, headers, null, timeoutMillis) { response ->
            if (response.code !in 200..299) {
                return@execute toHttpResponse(response)
            }
            val temp = blobStore.createTempFile(pluginId)
            var blobId: String? = null
            try {
                val source = response.body ?: throw IOException("响应体为空")
                var total = 0L
                source.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > PluginBlobStore.MAX_BLOB_BYTES) {
                                throw IOException("响应体超过上限（${PluginBlobStore.MAX_BLOB_BYTES / 1024 / 1024}MB）")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                blobId = blobStore.register(pluginId, temp, owned = true)
                HttpResponse(
                    status = response.code,
                    headers = response.headers.toMap(),
                    body = ByteArray(0),
                    blobId = blobId,
                    size = total
                )
            } catch (e: Exception) {
                // 落盘失败不泄漏暂存文件（blob 未登记则直接删除）
                if (blobId == null) temp.delete()
                throw e
            }
        }
    }

    override fun lastError(): String? = lastErrorMsg

    // ---- 内部 ----

    /** 域名白名单校验；拒绝时记录原因并触发用户的授权引导（与历史行为一致）。 */
    private fun authorize(url: String): Boolean {
        val pluginInfo = PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
        val declaredHosts = pluginInfo?.declaredHosts ?: emptyList()
        val authorizedHosts = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)
        val customHosts = ExtensionManager.getConfiguredNetworkHosts(context, pluginId).toSet()

        val reason = NetworkPolicy.check(url, emptySet(), declaredHosts, authorizedHosts, customHosts)
        if (reason != null) {
            lastErrorMsg = reason
            Log.w(TAG, "[$pluginId] 联网被拒绝: $reason")
            PluginErrorLog.logError(
                pluginId,
                "联网被拒绝",
                reason,
                category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.NETWORK_DENIED
            )
            PluginNetworkAuthHelper.onNetworkDenied(
                context, pluginId, pluginInfo?.name,
                NetworkPolicy.extractHost(url), reason
            )
            return false
        }
        return true
    }

    /**
     * 组装并执行请求（三个入口共用）：方法分派、超时、响应读取与错误落点。
     *
     * @param read 在响应仍打开时读取（返回值即本函数返回值）
     */
    private fun <T> execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: RequestBody?,
        timeoutMillis: Int?,
        read: (Response) -> T
    ): T? {
        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "DELETE" -> requestBuilder.delete()
                "HEAD" -> requestBuilder.head()
                "PUT" -> requestBuilder.put(body ?: ByteArray(0).toRequestBody())
                "POST" -> requestBuilder.post(body ?: ByteArray(0).toRequestBody())
                "PATCH" -> requestBuilder.patch(body ?: ByteArray(0).toRequestBody())
                "MKCOL" -> requestBuilder.method("MKCOL", null)
                "PROPFIND" -> requestBuilder.method("PROPFIND", null)
                else -> requestBuilder.get()
            }
            val call = effectiveClient(timeoutMillis).newCall(requestBuilder.build())
            if (timeoutMillis != null && timeoutMillis > 0) {
                call.timeout().timeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            }
            call.execute().use(read)
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: "request failed"
            Log.e(TAG, "[$pluginId] HTTP $method $url failed", e)
            PluginErrorLog.logError(
                pluginId,
                "HTTP 请求失败 ($method $url)",
                e.message ?: "request failed",
                e,
                category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.HTTP_ERROR
            )
            null
        }
    }

    private fun effectiveClient(timeoutMillis: Int?): OkHttpClient =
        if (timeoutMillis != null && timeoutMillis > 0) {
            client.newBuilder()
                .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .build()
        } else {
            client
        }

    /** 尊重调用方显式声明的 Content-Type（OkHttp 的 RequestBody 会用它覆盖 header）。 */
    private fun contentTypeOf(headers: Map<String, String>): String =
        headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/octet-stream"

    private fun toHttpResponse(response: Response): HttpResponse {
        val headers = HashMap<String, String>()
        response.headers.forEach { (k, v) -> headers[k] = v }
        val bytes = response.body?.bytes() ?: ByteArray(0)
        return HttpResponse(
            status = response.code,
            headers = headers,
            body = bytes
        )
    }
}

/**
 * 文件请求体：OkHttp 按段从磁盘流式读取，Content-Length 取文件长度（不启用 chunked）。
 *
 * 写入失败（网络中断）时不清空文件——MKCOL 重试路径需要重复读取同一 blob。
 */
private class FileRequestBody(
    private val file: File,
    private val mediaType: okhttp3.MediaType
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.source().use { sink.writeAll(it) }
    }
}
