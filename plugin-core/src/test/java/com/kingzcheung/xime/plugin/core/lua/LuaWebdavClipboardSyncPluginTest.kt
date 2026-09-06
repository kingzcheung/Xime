package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证 webdav-clipboard-sync 插件：WebDAV 协议（PUT/GET/MKCOL、Basic Auth、ETag
 * 条件拉取、409 目录不存在时自动 MKCOL）全在 Lua，宿主只提供 host.http / host.crypto
 * / host.config 原语。
 */
class LuaWebdavClipboardSyncPluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** 记录请求、可编程响应的 mock HTTP 宿主。 */
    private class MockHttpHostApi : HttpHostApi {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val requestBodies = mutableListOf<String>()
        val responseQueue = ArrayDeque<HttpResponse>()
        var lastErrorMsg: String? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            requests.add(Triple(method, url, headers))
            requestBodies.add(body?.toString(Charsets.UTF_8) ?: "")
            return responseQueue.removeFirstOrNull()
        }

        override fun lastError(): String? = lastErrorMsg
    }

    private class MockCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(20)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
        override fun utcTime(format: String): String = ""
        override fun epochSeconds(): Long = 1767225600
    }

    /** 测试专用宿主 API：log 输出到 stdout（unit test 中 android.util.Log 是 stub）。 */
    private class DebugHostApi(private val store: PluginConfigStore) : LuaHostApi {
        override val sdkVersion = "0.1.0"
        override fun log(message: String) { System.out.println("LUA_LOG: $message") }
        override fun logError(message: String) { System.err.println("LUA_ERROR: $message") }
        override fun configGet(key: String) = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys() = store.keys()
        override fun resourcePath(name: String) = null
        override fun resourceList(dir: String) = emptyList<String>()
        override fun jsonEncode(obj: Any?) = com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.encode(obj)
        override fun jsonDecode(json: String) = try {
            com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.decode(json)
        } catch (_: Exception) {
            null
        }
        override fun uuid() = "uuid"
    }

    private fun newRuntime(store: PluginConfigStore, http: MockHttpHostApi): LuaScriptRuntime {
        val dir = File("../plugins/webdav-clipboard-sync")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.webdav_clipboard_sync",
            dir,
            "main.lua",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = MockCryptoHostApi()
        )
        assertTrue("main.lua 应能加载", runtime.load())
        return runtime
    }

    private fun profileTable(text: String, hash: String): org.luaj.vm2.LuaTable {
        val table = org.luaj.vm2.LuaTable()
        table.set("type", "text")
        table.set("hash", hash)
        table.set("text", text)
        table.set("has_data", org.luaj.vm2.LuaValue.FALSE)
        table.set("size", org.luaj.vm2.LuaValue.valueOf(text.length.toDouble()))
        return table
    }

    @Test
    fun `main lua loads and exposes sync contract`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val schema = runtime.call("getSettingsSchema")
        assertTrue("应导出 getSettingsSchema", schema.istable())
        assertEquals(5, LuaScriptRuntime.tableToList(schema).size)
    }

    @Test
    fun `push sends PUT to webdav clipboard json with basic auth`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("username", "alice")
        store.set("password", "secret")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/dav/clipboard/current.json", url)
        val expectedAuth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray())
        assertEquals(expectedAuth, headers["Authorization"])
    }

    @Test
    fun `push creates missing directories via MKCOL on 409 then retries`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        // PUT → 409（目录不存在），随后 MKCOL 从 davUrl 之后逐级创建（/xime、/xime/clipboard，
        // 405=已存在、201=创建），再 PUT → 201
        http.responseQueue.addLast(HttpResponse(409))
        http.responseQueue.addLast(HttpResponse(405))
        http.responseQueue.addLast(HttpResponse(201))
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()

        assertTrue("push 应成功", ok)
        assertEquals(4, http.requests.size)
        val methods = http.requests.map { it.first }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), methods)
        // 验证 MKCOL 从 davUrl 之后开始，不触碰服务器根
        val mkcolUrls = http.requests.filter { it.first == "MKCOL" }.map { it.second }
        assertEquals(
            listOf(
                "https://192.168.1.50:8080/dav/xime",
                "https://192.168.1.50:8080/dav/xime/clipboard"
            ),
            mkcolUrls
        )
    }

    @Test
    fun `push creates missing directories via MKCOL on 404 then retries`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        // 部分服务器（Alist/Nextcloud 等）对 PUT 缺失父目录返回 404 而非 409，
        // 同样应触发 MKCOL 逐级创建后重试
        http.responseQueue.addLast(HttpResponse(404))
        http.responseQueue.addLast(HttpResponse(405))
        http.responseQueue.addLast(HttpResponse(201))
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()

        assertTrue("push 应成功", ok)
        val methods = http.requests.map { it.first }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), methods)
    }

    @Test
    fun `push honors remotePath in url`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/dav/xime/clipboard/current.json", url)
    }

    @Test
    fun `push sends JSON profile body`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()

        assertTrue("push 应成功", ok)
        val body = http.requestBodies[0]
        assertTrue("body 应为 JSON", body.startsWith("{"))
        assertTrue("body 应含 text", body.contains("\"hello\""))
        assertTrue("body 应含 hash", body.contains("\"abc\""))
    }

    @Test
    fun `pull returns profile on 200 json and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        val profileJson = """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(
            HttpResponse(200, mapOf("ETag" to "webdav-etag-1"), profileJson.toByteArray())
        )
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("pull 应返回 table", result.istable())
        val map = LuaScriptRuntime.tableToMap(result)
        assertEquals("远端内容", map["text"]?.tojstring())
        assertEquals("abc123", map["hash"]?.tojstring())
        assertEquals("desktop", map["source"]?.tojstring())
        assertEquals("webdav-etag-1", store.get("lastEtag"))
    }

    @Test
    fun `pull falls back to plain text for legacy file`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "etag-2"), "旧版纯文本".toByteArray()))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("pull 应返回 table", result.istable())
        val map = LuaScriptRuntime.tableToMap(result)
        assertEquals("旧版纯文本", map["text"]?.tojstring())
    }

    @Test
    fun `pull returns null on 304`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("lastEtag", "webdav-etag-1")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("304 应返回 nil", result.isnil())
        assertEquals(1, http.requests.size)
        val headers = http.requests[0].third
        assertEquals("webdav-etag-1", headers["If-None-Match"])
    }

    @Test
    fun `pull returns null on 404`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("404 应返回 nil", result.isnil())
    }

    @Test
    fun `testConnection uses PROPFIND on clipboard directory`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(207))
        val runtime = newRuntime(store, http)

        val error = runtime.call("testConnection").tojstring()

        assertFalse("207 视为连接成功: $error", error.contains("失败"))
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PROPFIND", method)
        assertEquals("https://192.168.1.50:8080/dav/clipboard", url)
        assertEquals("0", headers["Depth"])
    }

    @Test
    fun `testConnection reports auth failure`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(401))
        val runtime = newRuntime(store, http)

        val error = runtime.call("testConnection").tojstring()

        assertTrue("应报告认证失败: $error", error.contains("认证失败"))
    }

    @Test
    fun `testConnection reports missing config`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val error = runtime.call("testConnection").tojstring()
        assertTrue("未配置时应报告错误: $error", error.contains("未配置"))
    }

    @Test
    fun `testConnection succeeds on 404 (server reachable, file not yet created)`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        val error = runtime.call("testConnection").tojstring()

        assertFalse("404 视为连接成功", error.contains("失败"))
    }

    @Test
    fun `push returns false when url not configured`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()
        assertFalse("未配置时应失败", ok)
    }

    @Test
    fun `pull returns nil when url not configured`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val result = runtime.call("pull")
        assertTrue("未配置时应返回 nil", result.isnil())
    }
}
