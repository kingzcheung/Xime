package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SyncManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** librime 快照（`UserDb::Backup` 产物）：行 = `码 + 空格 \t 词 \t c=.. d=.. t=..`。 */
    private val snapshotContent = listOf(
        "# Rime user dictionary",
        "#@/db_name\tluna_pinyin",
        "#@/db_type\tuserdb",
        "#@/rime_version\t1.17.0",
        "#@/user_id\t11111111-2222-3333-4444-555555555555",
        "ni hao \t你好\tc=3 d=4e-08 t=0",
    ).joinToString("\n")

    /** librime 码表导出（`UserDictManager::Export` 产物）：行 = `词 \t 码 \t 频率`。 */
    private fun codeTableBytes(dict: String = "luna_pinyin"): ByteArray = listOf(
        "# Rime user dictionary export",
        "#@/db_name\t$dict",
        "#@/db_type\tuserdb",
        "你好\tni hao\t3",
    ).joinToString("\n").toByteArray()

    private fun snapshotBytes(dict: String = "pinyin_simp", renamed: String? = null): ByteArray = listOf(
        "# Rime user dictionary",
        "#@/db_name\t$dict",
        "#@/db_type\tuserdb",
        "#@/user_id\t11111111-2222-3333-4444-555555555555",
        "ni hao \t你好\tc=3 d=4e-08 t=0",
    ).joinToString("\n").let { if (renamed != null) it.replace("#@/db_name\t$dict", "#@/db_name\t$dict") else it }
        .toByteArray()

    // ---- ensureInstallationFile：三态 ----

    @Test
    fun `ensure creates yaml when missing`() {
        val yaml = File(tempDir.root, "rime/installation.yaml")
        val id = SyncManager.ensureInstallationFile(yaml, "id-1")
        assertEquals("id-1", id)
        assertTrue(yaml.exists())
        assertTrue(yaml.readText().contains("installation_id: \"id-1\""))
    }

    @Test
    fun `ensure keeps existing file when id matches`() {
        val yaml = File(tempDir.root, "rime/installation.yaml")
        yaml.parentFile.mkdirs()
        yaml.writeText("installation_id: \"id-1\"\nrime_version: 1.17.0\n")
        SyncManager.ensureInstallationFile(yaml, "id-1")
        assertTrue(yaml.readText().contains("rime_version: 1.17.0"))
    }

    @Test
    fun `ensure rewrites when id differs`() {
        val yaml = File(tempDir.root, "rime/installation.yaml")
        yaml.parentFile.mkdirs()
        yaml.writeText("installation_id: \"old-id\"\nrime_version: 1.17.0\n")
        SyncManager.ensureInstallationFile(yaml, "new-id")
        val text = yaml.readText()
        assertTrue(text.contains("installation_id: \"new-id\""))
        assertFalse(text.contains("old-id"))
    }

    // ---- packSyncDir / unpackArchive 往返 ----

    @Test
    fun `pack returns null when sync dir missing or empty`() {
        val rimeDir = tempDir.newFolder("rime")
        assertNull(SyncManager.packSyncDir(rimeDir))
        File(rimeDir, "sync").mkdirs()
        assertNull(SyncManager.packSyncDir(rimeDir))
    }

    @Test
    fun `pack and unpack round trip preserves snapshot structure`() {
        val rimeDir = tempDir.newFolder("rime")
        val snapshot = File(rimeDir, "sync/id-A/luna_pinyin.userdb.txt")
        snapshot.parentFile.mkdirs()
        snapshot.writeText(snapshotContent)

        val bytes = SyncManager.packSyncDir(rimeDir)!!
        assertTrue(SyncManager.unpackArchive(File(tempDir.root, "out"), bytes) == 1)
        val restored = File(tempDir.root, "out/id-A/luna_pinyin.userdb.txt")
        assertEquals(snapshotContent, restored.readText())
    }

    @Test
    fun `unpack strips leading sync prefix`() {
        val base = tempDir.newFolder("base")
        val bytes = zipOf(mapOf("sync/id-A/luna_pinyin.userdb.txt" to snapshotContent))
        assertEquals(1, SyncManager.unpackArchive(base, bytes))
        assertTrue(File(base, "id-A/luna_pinyin.userdb.txt").exists())
    }

    // ---- 流式（文件）入口：远端推送/拉取用，包体不进内存 ----

    @Test
    fun `pack to file and unpack from file round trip`() {
        val rimeDir = tempDir.newFolder("rime-stream")
        val snapshot = File(rimeDir, "sync/id-B/luna_pinyin.userdb.txt")
        snapshot.parentFile.mkdirs()
        snapshot.writeText(snapshotContent)
        val archive = File(tempDir.root, "snapshot.zip")

        assertEquals(1, SyncManager.packSyncDirToFile(rimeDir, archive))
        assertTrue("打包产物应落盘", archive.isFile)

        val out = File(tempDir.root, "out-stream")
        assertEquals(1, SyncManager.unpackArchive(out, archive))
        assertEquals(snapshotContent, File(out, "id-B/luna_pinyin.userdb.txt").readText())
    }

    @Test
    fun `pack to file returns zero and removes empty archive`() {
        val rimeDir = tempDir.newFolder("rime-empty")
        File(rimeDir, "sync").mkdirs()
        val archive = File(tempDir.root, "empty.zip")

        assertEquals(0, SyncManager.packSyncDirToFile(rimeDir, archive))
        assertFalse("空包不应留下文件", archive.exists())
    }

    @Test
    fun `unpack from file rejects path traversal entries`() {
        val base = tempDir.newFolder("base-stream")
        val archive = File(tempDir.root, "evil.zip")
        archive.writeBytes(zipOf(mapOf("sync/../../evil.txt" to "boom")))

        assertThrows(SecurityException::class.java) { SyncManager.unpackArchive(base, archive) }
        assertFalse(File(tempDir.root, "evil.txt").exists())
    }

    // ---- 导入格式识别（快照与码表列序不同，混用会把码/词写反） ----

    @Test
    fun `classify recognizes zip snapshot code table and unknown`() {
        assertEquals(
            SyncManager.SnapshotFileKind.ZIP,
            SyncManager.classifySnapshotFile("snap.zip", zipOf(mapOf("sync/x/a.userdb.txt" to "x")))
        )
        assertEquals(
            "用户词典备份/本机 sync 产物是真快照",
            SyncManager.SnapshotFileKind.SNAPSHOT,
            SyncManager.classifySnapshotFile("pinyin_simp.userdb.txt", snapshotBytes())
        )
        assertEquals(
            "用户词典导出的是词条码表",
            SyncManager.SnapshotFileKind.CODE_TABLE,
            SyncManager.classifySnapshotFile("pinyin_simp.txt", codeTableBytes())
        )
        assertEquals(
            "无头码表按 <词典名>.txt 认",
            SyncManager.SnapshotFileKind.CODE_TABLE,
            SyncManager.classifySnapshotFile("pinyin_simp.txt", "你好\tni hao\t3\n".toByteArray())
        )
        assertEquals(
            SyncManager.SnapshotFileKind.UNKNOWN,
            SyncManager.classifySnapshotFile("random.bin", byteArrayOf(0, 1, 2, 3))
        )
        assertEquals(
            "无头又非 .txt 无法识别",
            SyncManager.SnapshotFileKind.UNKNOWN,
            SyncManager.classifySnapshotFile("notes.md", "hello world".toByteArray())
        )
    }

    @Test
    fun `snapshot target name prefers db_name metadata over file name`() {
        // 备份文件被用户改名（如 pinyin_simp(1).userdb.txt）也能归位
        assertEquals(
            "pinyin_simp.userdb.txt",
            SyncManager.snapshotTargetName("pinyin_simp(1).userdb.txt", snapshotBytes(dict = "pinyin_simp"))
        )
        // 没有元数据时退回文件名（老版本/手工生成的头只有描述行）
        assertEquals(
            "wubi86.userdb.txt",
            SyncManager.snapshotTargetName(
                "wubi86.userdb.txt",
                "# Rime user dictionary\n劝学\tclip\tc=1 d=1e-08 t=0\n".toByteArray()
            )
        )
        // 无法确定词典名 → null（调用方明确报错，不做"看起来成功"的静默处理）
        assertNull(SyncManager.snapshotTargetName("snapshot.txt", "data".toByteArray()))
    }

    @Test
    fun `code table dict name prefers db_name metadata then file name`() {
        assertEquals("luna_pinyin", SyncManager.codeTableDictName("任意名字.txt", codeTableBytes("luna_pinyin")))
        assertEquals("wubi86", SyncManager.codeTableDictName("wubi86.txt", "劝学\tclip\t1\n".toByteArray()))
        assertNull(SyncManager.codeTableDictName("notes.md", "hello".toByteArray()))
    }

    /**
     * 回归：librime 会把用户目录所有顶层 yaml/txt（含 20MB+ 方案词典）镜像进
     * `sync/<user_id>/`（`backup_config_files`），那批镜像只写不读，不是词库数据。
     * 打包只收 `*.userdb.txt`，否则词库快照包会被塞进词典副本（实测 8.4MB→23MB 原始）。
     */
    @Test
    fun `pack only includes userdb snapshots not config mirrors`() {
        val rimeDir = tempDir.newFolder("rime-pack")
        val id = "11111111-2222-3333-4444-555555555555"
        val syncDir = File(rimeDir, "sync/$id")
        syncDir.mkdirs()
        File(syncDir, "pinyin_simp.userdb.txt").writeText(snapshotContent)
        // 镜像：librime 的 backup_config_files 产物，不该进包
        File(syncDir, "pinyin_simp.dict.yaml").writeText("name: pinyin_simp\n")
        File(syncDir, "default.yaml").writeText("schema_list:\n")
        File(syncDir, "default.custom.yaml").writeText("patch:\n")

        val archive = File(tempDir.root, "snap.zip")
        assertEquals("只应打包 1 个快照文件", 1, SyncManager.packSyncDirToFile(rimeDir, archive))

        val out = tempDir.newFolder("out-pack")
        assertEquals(1, SyncManager.unpackArchive(out, archive))
        assertTrue(File(out, "$id/pinyin_simp.userdb.txt").exists())
        assertFalse("词典镜像不得进包", File(out, "$id/pinyin_simp.dict.yaml").exists())
        assertFalse("配置镜像不得进包", File(out, "$id/default.yaml").exists())
    }

    @Test
    fun `pack returns null when sync dir only has mirrors`() {
        val rimeDir = tempDir.newFolder("rime-mirror-only")
        val syncDir = File(rimeDir, "sync/id-A")
        syncDir.mkdirs()
        File(syncDir, "default.yaml").writeText("schema_list:\n")

        assertNull("只有镜像时没有可同步的词库数据", SyncManager.packSyncDir(rimeDir))
    }

    // ---- 解包安全 ----
    @Test
    fun `unpack rejects path traversal entries`() {
        val base = tempDir.newFolder("base")
        val bytes = zipOf(mapOf("sync/../../evil.txt" to "boom"))
        assertThrows(SecurityException::class.java) { SyncManager.unpackArchive(base, bytes) }
        assertFalse(File(tempDir.root, "evil.txt").exists())
        assertFalse(File(tempDir.root, "base/evil.txt").exists())
    }

    @Test
    fun `unpack skips empty and directory entries`() {
        val base = tempDir.newFolder("base")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("sync/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("sync/id-A/"))
            zos.closeEntry()
        }
        assertEquals(0, SyncManager.unpackArchive(base, bos.toByteArray()))
    }

    // ---- 远端条目前缀过滤 ----

    @Test
    fun `remote sync prefix filter distinguishes snapshots from config backups`() {
        assertTrue(SyncManager.isRemoteSyncEntry("rime-sync-abc-123.zip"))
        assertFalse(SyncManager.isRemoteSyncEntry("rime-snapshot.zip"))
        assertFalse(SyncManager.isRemoteSyncEntry("Xime完整备份-2026-09-25.zip"))
    }
}
