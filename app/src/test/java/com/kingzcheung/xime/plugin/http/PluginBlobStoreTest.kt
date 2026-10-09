package com.kingzcheung.xime.plugin.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 宿主大文件句柄仓库：id 绑定插件、所有权语义、残留清理、体积上限常量。
 *
 * 安全边界：插件拿不到路径；跨插件引用必须解析失败（否则 A 插件可读 B 插件的备份包）。
 */
class PluginBlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): PluginBlobStore = PluginBlobStore(File(tmp.root, "blobs"))

    @Test
    fun `登记的 blob 仅登记方可解析`() {
        val store = newStore()
        val file = File(tmp.root, "archive.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = store.register("plugin-a", file)

        assertEquals("登记方应解析到原文件", file, store.resolve("plugin-a", id))
        assertNull("其它插件不得解析（跨插件引用拒绝）", store.resolve("plugin-b", id))
        assertNull("不存在的 id 返回 null", store.resolve("plugin-a", "no-such-id"))
    }

    @Test
    fun `owned=false 释放只注销不删文件（备份包由调用方清理）`() {
        val store = newStore()
        val file = File(tmp.root, "archive.zip").apply { writeBytes(byteArrayOf(1)) }
        val id = store.register("plugin-a", file, owned = false)

        store.release(id)

        assertTrue("文件所有权属调用方，释放后仍存在", file.isFile)
        assertNull("句柄已注销", store.resolve("plugin-a", id))
    }

    @Test
    fun `owned=true 释放连同文件一起删除（流式下载产物）`() {
        val store = newStore()
        val temp = store.createTempFile("plugin-a").apply { writeBytes(byteArrayOf(1, 2)) }
        val id = store.register("plugin-a", temp, owned = true)

        assertEquals(temp, store.resolve("plugin-a", id))
        store.release(id)

        assertFalse("仓库自有文件应被删除", temp.exists())
        assertNull(store.resolve("plugin-a", id))
        store.release(id) // 幂等：重复释放不抛异常
    }

    @Test
    fun `临时文件落在仓库目录内且互不相同`() {
        val store = newStore()
        val a = store.createTempFile("plugin-a")
        val b = store.createTempFile("plugin-a")

        assertEquals(File(tmp.root, "blobs"), a.parentFile)
        assertTrue("多次申请应得到不同文件", a.name != b.name)
    }

    @Test
    fun `文件被外部删除后解析返回 null 并清理句柄`() {
        val store = newStore()
        val file = File(tmp.root, "gone.zip").apply { writeBytes(byteArrayOf(1)) }
        val id = store.register("plugin-a", file)

        assertTrue(file.delete())

        assertNull(store.resolve("plugin-a", id))
    }

    @Test
    fun `初始化清理超过保留期的残留暂存文件`() {
        val root = File(tmp.root, "blobs").apply { mkdirs() }
        val stale = File(root, "blob-old.tmp").apply { writeBytes(byteArrayOf(1)) }
        val fresh = File(root, "blob-new.tmp").apply { writeBytes(byteArrayOf(1)) }
        val old = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        assertTrue(stale.setLastModified(old))

        PluginBlobStore(root)

        assertFalse("48 小时前的残留应被清理", stale.exists())
        assertTrue("新文件保留", fresh.exists())
    }

    @Test
    fun `单次流式下载上限为 512MB`() {
        assertNotNull(PluginBlobStore.MAX_BLOB_BYTES)
        assertEquals(512L * 1024 * 1024, PluginBlobStore.MAX_BLOB_BYTES)
    }
}
