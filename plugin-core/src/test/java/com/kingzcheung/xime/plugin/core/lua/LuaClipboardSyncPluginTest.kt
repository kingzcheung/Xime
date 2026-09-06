package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证 ximed-clipboard-sync 插件：协议逻辑（HTTP 端点、Basic Auth、ETag 条件拉取）
 * 全在 Lua，宿主只提供 host.http / host.crypto / host.config / host.json 原语。
 */
class LuaClipboardSyncPluginTest {

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
        override fun jsonDecode(json: String) = com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.decode(json)
        override fun uuid() = "uuid"
    }

    private fun newRuntime(store: PluginConfigStore, http: MockHttpHostApi): LuaScriptRuntime {
        val dir = File("../plugins/ximed-clipboard-sync")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.ximed_sync",
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
        assertEquals(4, LuaScriptRuntime.tableToList(schema).size)
    }

    @Test
    fun `push sends PUT to ximed endpoint with basic auth`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        store.set("username", "alice")
        store.set("password", "secret")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileTable("hello", "abc")).toboolean()

        assertTrue("push 应成功", ok)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/api/clipboard", url)
        val expectedAuth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray())
        assertEquals(expectedAuth, headers["Authorization"])
    }

    @Test
    fun `pull returns profile on 200 and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        val http = MockHttpHostApi()
        val profileJson = """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "etag-1"), profileJson.toByteArray()))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("pull 应返回 table", result.istable())
        val map = LuaScriptRuntime.tableToMap(result)
        assertEquals("远端内容", map["text"]?.tojstring())
        assertEquals("abc123", map["hash"]?.tojstring())
        assertEquals("etag-1", store.get("lastEtag"))
    }

    @Test
    fun `pull returns null on 304`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        store.set("lastEtag", "etag-1")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("304 应返回 nil", result.isnil())
        assertEquals(1, http.requests.size)
        val headers = http.requests[0].third
        assertEquals("etag-1", headers["If-None-Match"])
    }

    @Test
    fun `testConnection reports auth failure`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
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
}
