package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包范围（**只有一个范围：完整备份**）——纯函数表驱动锚定。
 *
 * 包里应该有：设置与用户补丁、方案本体与资源、本机自造词快照。
 * 包里不该有（派生/可由引擎重建/另有合并通道）：`build/`、`*.bin/.gram/.db*`、
 * `*.userdb/`（leveldb 用户词典库）、派生的 `default.yaml`、其他设备的 `sync/<其它 id>/`、
 * 以及 librime 镜像进 sync 的配置/词典副本。
 */
class RimeExportScopeTest {

    private val localId = "11111111-2222-3333-4444-555555555555"
    private val otherId = "99999999-8888-7777-6666-555555555555"

    private fun included(path: String, id: String = localId) =
        RimeExportManager.includeRimeEntry(path, id)

    // ---- 设置与用户补丁 ----

    @Test
    fun `config patches are included`() {
        val patches = listOf(
            "default.custom.yaml",
            "pinyin_simp.custom.yaml",
            "t9_pinyin.custom.yaml",
            "wubi86.custom.yaml",
            "keyboard.custom.yaml", // 其他 rime 前端留下的按键/字体用户配置
            "desktop.custom.yaml",
            "xime.custom.yaml", // 键盘布局/按键/字体等用户配置
            "user.yaml",
            "installation.yaml",
            "custom_phrase.txt",
            "symbols.yaml"
        )
        for (path in patches) {
            assertTrue("完整备份应含 $path", included(path))
        }
    }

    // ---- 方案本体与资源 ----

    @Test
    fun `scheme body and visual resources are included`() {
        val schemeBody = listOf(
            "pinyin_simp.schema.yaml",
            "pinyin_simp.dict.yaml",
            "stroke.dict.yaml",
            "opencc/ts.txt",
            "lua/t9_filter.lua",
            "fonts/MyFont.ttf",
            "themes/bg.jpg"
        )
        for (path in schemeBody) {
            assertTrue("完整备份应含方案本体/资源 $path", included(path))
        }
    }

    // ---- 排除：派生与词典库 ----

    @Test
    fun `derived artifacts and leveldb are excluded`() {
        val excluded = listOf(
            "build/default.table.bin",      // librime 编译产物
            "default.yaml",                 // 由 default.custom.yaml + 内置基座派生
            "pinyin_simp.table.bin",
            "t9.gram",
            "user.db",
            "user.db-wal",
            "user.db-shm",
            "pinyin_simp.userdb/MANIFEST-000002",
            "pinyin_simp.userdb/CURRENT",
            "t9_digit.userdb/000005.ldb"
        )
        for (path in excluded) {
            assertFalse("完整备份不应含 $path", included(path))
        }
    }

    // ---- 自造词快照：只认本机 installation_id、只收快照本身 ----

    @Test
    fun `dict snapshots only for the local installation id`() {
        val local = "sync/$localId/pinyin_simp.userdb.txt"
        val foreign = "sync/$otherId/pinyin_simp.userdb.txt"
        val imported = "sync/imported/abc/luna_pinyin.userdb.txt"

        assertTrue("本机快照应进包", included(local))
        assertFalse("其它设备的快照不进包", included(foreign))
        assertFalse("导入的历史快照不进包", included(imported))
        assertFalse("installation id 缺失时不带任何 sync 内容", included(local, id = ""))
        assertFalse("前缀必须整段匹配", included("sync/${localId}x/a.userdb.txt"))
        assertFalse("sync 下的散文件不进包", included("sync/somefile.txt"))
    }

    /**
     * 回归（用户实测 zip 8.4MB）：librime 的 `sync_user_data` 会把用户目录下所有顶层
     * `.yaml`/`.txt`（含方案词典）镜像进 `sync/<user_id>/`。这些镜像**不是**自造词快照，
     * 包内已由方案本体/配置各自覆盖，绝不能跟着快照一起塞进来。
     */
    @Test
    fun `sync dir mirrors are not treated as dict snapshots`() {
        val mirrors = listOf(
            "sync/$localId/pinyin_simp.dict.yaml",
            "sync/$localId/pinyin_simp_ext.dict.yaml",
            "sync/$localId/stroke.dict.yaml",
            "sync/$localId/pinyin_simp.schema.yaml",
            "sync/$localId/default.yaml",
            "sync/$localId/default.custom.yaml",
            "sync/$localId/xime.custom.yaml",
            "sync/$localId/symbols.yaml",
            "sync/$localId/custom_phrase.txt",
            "sync/$localId/installation.yaml"
        )
        for (path in mirrors) {
            assertFalse("镜像文件不进包: $path", included(path))
        }
        assertTrue("同目录下的快照本身要进包", included("sync/$localId/pinyin_simp.userdb.txt"))
    }

    @Test
    fun `windows style separators are normalized`() {
        assertTrue(included("default.custom.yaml"))
        assertFalse(included("build\\default.table.bin"))
        assertFalse(included("pinyin_simp.userdb\\MANIFEST-000002"))
    }
}
