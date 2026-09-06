package com.kingzcheung.xime.plugin.core.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaTable
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File

/**
 * WebDAV 备份插件 listBackups 的 luaj 回归测试。
 *
 * 背景（真机两连 bug）：①命名空间模式写成 `[dD:]?`（单字符可选）导致
 * `<D:response>` 一个块都匹配不上，207 返回却解析出空列表；②跨过 gsub
 * 非 ASCII 怪癖的 href 解码。本测试用坚果云风格的 207 响应锁定解析行为。
 */
class LuaWebdavBackupListTest {

    private val propfindXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/xime_backup/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                <D:getlastmodified>Sat, 06 Sep 2026 02:30:58 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/xime_backup/Xime%E9%85%8D%E7%BD%AE-2026-09-06.zip</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>5598773</D:getcontentlength>
                <D:getlastmodified>Sat, 06 Sep 2026 02:30:58 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    private fun loadPlugin(): Pair<LuaTable, org.luaj.vm2.Globals> {
        val globals = JsePlatform.standardGlobals()
        // host 打桩：config 返回坚果云配置，http.request 返回 canned 207 响应并记录请求
        val bootstrap = """
            lastRequest = nil
            host = {
              config = {
                get = function(key)
                  if key == "url" then return "https://dav.jianguoyun.com/dav/" end
                  if key == "username" then return "user" end
                  if key == "password" then return "pass" end
                  if key == "remote_path" then return "/xime_backup" end
                  return nil
                end,
              },
              crypto = { base64 = function(s) return "dXNlcjpwYXNz" end },
              http = {
                request = function(method, url, headers, body)
                  lastRequest = { method = method, url = url }
                  return { status = 207, text = [[PROPXML]], body = "" }
                end,
                lastError = function() return nil end,
              },
            }
        """.trimIndent().replace("PROPXML", propfindXml)
        globals.load(bootstrap).call()
        val src = File("../plugins/webdav-backup/main.lua")
        val plugin = globals.load(src.readText()).call() as LuaTable
        return plugin to globals
    }

    @Test
    fun `listBackups parses jianguoyun 207 response`() {
        val (plugin, globals) = loadPlugin()
        val items = plugin.get("listBackups").call() as LuaTable
        val lastRequest = globals.get("lastRequest") as LuaTable

        // 目录条目与备份目录本身被过滤，仅剩文件
        assertEquals(1, items.length())
        val item = items.get(1) as LuaTable
        assertEquals("Xime配置-2026-09-06.zip", item.get("name").tojstring())
        assertEquals("/dav/xime_backup/Xime配置-2026-09-06.zip", item.get("id").tojstring())
        assertEquals(5598773L, item.get("size").tolong())
        assertTrue(item.get("createdAt").tolong() > 0)
        // PROPFIND 打到了带 /dav 前缀的正确地址
        assertEquals("PROPFIND", lastRequest.get("method").tojstring())
        assertEquals("https://dav.jianguoyun.com/dav/xime_backup", lastRequest.get("url").tojstring())
    }
}
