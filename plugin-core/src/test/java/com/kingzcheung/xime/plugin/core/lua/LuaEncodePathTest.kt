package com.kingzcheung.xime.plugin.core.lua

import org.junit.Assert.assertEquals
import org.junit.Test
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File

/**
 * WebDAV 备份插件 encodePath 的 luaj 回归测试。
 *
 * 背景：真机 PUT 中文文件名 400——luaj 的 gsub 模式匹配器对非 ASCII 字节流
 * 匹配单元错乱（"配置" 的 E9/E7 首字节漏编码），必须用 string.byte 逐字节编码。
 * 本测试加载真实 main.lua（host 打桩），锁定编码行为。
 */
class LuaEncodePathTest {

    private fun encodePath(raw: String): String {
        val globals = JsePlatform.standardGlobals()
        // host 打桩：main.lua 顶层只定义函数，host 在调用期才被访问
        val host = LuaTable()
        host.set("config", LuaTable())
        globals.set("host", host)
        // 测试工作目录为 plugin-core/，插件源码在主仓库 plugins/ 下
        val src = File("../plugins/webdav-backup/main.lua")
        val plugin = globals.load(src.readText()).call() as LuaTable
        val fn = plugin.get("_encodePath")
        return fn.call(LuaString.valueOf(raw.toByteArray(Charsets.UTF_8))).tojstring()
    }

    @Test
    fun `encodes chinese filename to utf-8 percent escapes`() {
        assertEquals(
            "Xime%E9%85%8D%E7%BD%AE-2026-09-06.zip",
            encodePath("Xime配置-2026-09-06.zip")
        )
    }

    @Test
    fun `keeps unreserved and path chars intact`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/xime_backup",
            encodePath("https://dav.jianguoyun.com/dav/xime_backup")
        )
        assertEquals("a-b._~/z:9", encodePath("a-b._~/z:9"))
    }

    @Test
    fun `encodes space and percent`() {
        assertEquals("my%20file%2520.zip", encodePath("my file%20.zip"))
    }
}
