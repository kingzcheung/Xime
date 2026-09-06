package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.lua.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaString
import java.io.File
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 验证 tencent-asr：腾讯云实时语音识别 V2（WebSocket）的签名鉴权、握手状态机、
 * 音频直发与句子结果解析全部在 Lua 承载，宿主仅提供 host.ws / host.crypto 原语。
 */
class LuaTencentAsrPluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockWsHostApi : WsHostApi {
        val sentBinaries = mutableListOf<ByteArray>()
        val sentTexts = mutableListOf<String>()
        var connectedUrl: String? = null
        var hostListener: WsHostListener? = null
        var closed = false

        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            connectedUrl = url
            hostListener = listener
            return true
        }
        override fun sendText(message: String) { sentTexts.add(message) }
        override fun sendBinary(data: ByteArray) { sentBinaries.add(data) }
        override fun close() { closed = true }
        override fun getState(): Int = 2
        override fun lastError(): String? = null
    }

    /** 真实 HMAC-SHA1/Base64（验证签名端到端正确），时间固定便于断言 timestamp/expired。 */
    private class RealHmacCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(data)
        }
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
        override fun utcTime(format: String): String = ""
        override fun epochSeconds(): Long = 1767225600
    }

    private class ResultCollector : AsrPluginListener {
        var finalText = ""
        var partialText = ""
        var error: String? = null
        override fun onFinal(text: String) { finalText = text }
        override fun onPartial(text: String) { partialText = text }
        override fun onError(message: String) { error = message }
    }

    private fun newRuntime(store: PluginConfigStore, ws: WsHostApi): LuaScriptRuntime =
        LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.tencent_asr",
            File("../plugins/tencent-asr"),
            "main.lua",
            store,
            wsHostApi = ws,
            cryptoHostApi = RealHmacCryptoHostApi()
        )

    /** 解析连接 URL 的 query 为（已百分号解码的）键值对。 */
    private fun parseQuery(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        return query.split("&").filter { it.isNotEmpty() }.associate {
            val key = it.substringBefore("=")
            val value = URLDecoder.decode(it.substringAfter("="), "UTF-8")
            key to value
        }
    }

    @Test
    fun `tencent lua plugin owns signature handshake and sentence protocol`() {
        val dir = File("../plugins/tencent-asr")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store, mock)
        assertTrue("main.lua 应能加载", runtime.load())

        assertTrue("未配置时不就绪", !runtime.call("isConfigured").toboolean())

        // 设置 schema 非空且包含腾讯三要素
        val schema = LuaScriptRuntime.tableToList(runtime.call("getSettingsSchema"))
        val schemaKeys = schema.map { LuaScriptRuntime.tableToMap(it)["key"]?.tojstring() }
        assertTrue(schemaKeys.containsAll(listOf("appId", "secretId", "secretKey", "engineModelType")))
        assertTrue("hotwordList 应可选",
            schema.none {
                val field = LuaScriptRuntime.tableToMap(it)
                field["key"]?.tojstring() == "hotwordList" && field["required"]?.toboolean() == true
            })

        val collector = ResultCollector()
        runtime.asrResultCallback = collector

        // 未配置 → start 失败并 emitError
        assertTrue("start 应失败（未配置）", !runtime.call("start").toboolean())
        assertEquals("未配置 AppID / SecretId / SecretKey，请在插件设置中填写", collector.error)

        // 配置后 start → 连接腾讯域名，query 携带签名参数
        store.set("appId", "1234567890")
        store.set("secretId", "AKIDtest0000")
        store.set("secretKey", "test-secret")
        store.set("hotwordList", "语音|10,ASR|5")
        assertTrue("start 应成功", runtime.call("start").toboolean())
        val url = mock.connectedUrl!!
        assertTrue("连接地址应为腾讯 ASR 域名",
            url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1234567890?"))

        val query = parseQuery(url)
        assertEquals("16k_zh_en_2.0", query["engine_model_type"])
        assertEquals("AKIDtest0000", query["secretid"])
        assertEquals("1", query["voice_format"])
        assertEquals("1", query["needvad"])
        assertEquals("1767225600", query["timestamp"])
        assertEquals("1767312000", query["expired"])
        assertEquals("语音|10,ASR|5", query["hotword_list"])
        assertTrue("应带 voice_id（UUID）", query["voice_id"]!!.contains("-"))
        val nonce = query["nonce"]!!.toLong()
        assertTrue("nonce 应为 1~1e9 的正整数", nonce in 1..999999999)

        // 签名端到端校验：按解码后参数重建签名原文，重算 HMAC-SHA1+Base64
        val signStr = "asr.cloud.tencent.com/asr/v2/1234567890?" +
            query.filterKeys { it != "signature" }.toSortedMap().entries.joinToString("&") {
                "${it.key}=${it.value}"
            }
        val expectedSig = RealHmacCryptoHostApi().base64(
            RealHmacCryptoHostApi().hmacSha1("test-secret".toByteArray(), signStr.toByteArray(Charsets.UTF_8))
        )
        assertEquals("signature 应与签名原文重算一致", expectedSig, query["signature"])

        // onOpen 不发送任何数据（腾讯由服务端先发握手文本帧）
        mock.hostListener?.onOpen()
        assertEquals("onOpen 不应发送数据", 0, mock.sentBinaries.size)
        assertEquals(0, mock.sentTexts.size)

        // 握手前音频 → 缓冲
        runtime.call("processAudioChunk", LuaString.valueOf(byteArrayOf(1, 2, 3)))
        assertEquals("握手前应缓冲音频", 0, mock.sentBinaries.size)

        // 握手成功文本帧 → 补发缓冲音频，之后音频原样直发（二进制 PCM）
        mock.hostListener?.onMessage("""{"code":0,"message":"success","voice_id":"abc"}""")
        assertEquals("握手成功应补发缓冲音频", 1, mock.sentBinaries.size)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(mock.sentBinaries[0]))
        runtime.call("processAudioChunk", LuaString.valueOf(byteArrayOf(4, 5)))
        assertEquals(2, mock.sentBinaries.size)
        assertTrue(byteArrayOf(4, 5).contentEquals(mock.sentBinaries[1]))

        // 句子结果：sentence_type=0 → partial；=1 → final
        mock.hostListener?.onMessage(
            """{"code":0,"voice_id":"abc","sentences":[{"sentence":"实时语音","sentence_type":0,"sentence_id":0,"speaker_id":-1,"start_time":0,"end_time":800}]}"""
        )
        assertEquals("不确定句应走 partial", "实时语音", collector.partialText)
        assertEquals("", collector.finalText)
        mock.hostListener?.onMessage(
            """{"code":0,"voice_id":"abc","sentences":[{"sentence":"实时语音识别。","sentence_type":1,"sentence_id":0,"speaker_id":0,"start_time":0,"end_time":2850}]}"""
        )
        assertEquals("确定句应走 final", "实时语音识别。", collector.finalText)

        // final=1 → 识别结束，关闭连接
        mock.hostListener?.onMessage("""{"code":0,"voice_id":"abc","final":1}""")
        assertTrue("final 后应关闭连接", mock.closed)

        // 服务端错误帧 → emitError（含错误码）
        mock.hostListener?.onMessage("""{"code":4008,"message":"后台识别服务器音频分片等待超时","voice_id":"abc"}""")
        assertTrue("错误应上报", (collector.error ?: "").contains("4008"))
        assertTrue((collector.error ?: "").contains("音频分片等待超时"))

        // stop → 发送 {"type":"end"} 结束通知
        mock.closed = false
        assertTrue(runtime.call("start").toboolean())
        runtime.call("stop")
        assertEquals("stop 应发送结束通知", 1, mock.sentTexts.size)
        assertEquals("""{"type":"end"}""", mock.sentTexts[0])

        // cancel → 直接关闭
        runtime.call("cancel")
        assertTrue("cancel 应关闭连接", mock.closed)
    }

    @Test
    fun `handshake failure code closes connection without audio`() {
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        store.set("appId", "1234567890")
        store.set("secretId", "AKIDtest0000")
        store.set("secretKey", "test-secret")
        val runtime = newRuntime(store, mock)
        assertTrue(runtime.load())
        val collector = ResultCollector()
        runtime.asrResultCallback = collector

        assertTrue(runtime.call("start").toboolean())
        mock.hostListener?.onOpen()
        // 鉴权失败握手帧（code 非 0）→ 上报错误并断开，音频不应外发
        mock.hostListener?.onMessage(
            """{"code":4004,"message":"签名过期","voice_id":"abc"}"""
        )
        assertTrue((collector.error ?: "").contains("4004"))
        assertTrue("握手失败应断开", mock.closed)
        runtime.call("processAudioChunk", LuaString.valueOf(byteArrayOf(9, 9)))
        assertEquals("断开后音频不应外发", 0, mock.sentBinaries.size)
    }
}
