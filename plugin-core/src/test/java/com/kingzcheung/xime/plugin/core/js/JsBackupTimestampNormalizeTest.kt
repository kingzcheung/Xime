package com.kingzcheung.xime.plugin.core.js

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远端时间戳单位归一化（真机反馈："列表里创建日期显示 1970 年"）。
 *
 * 契约（[com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry.createdAt]）是**毫秒**，
 * 但插件可能给**秒**：webdav-backup v3.0.0 的 `epochFromParts` 返回秒（1.76e9），
 * 宿主按毫秒 `Date()` 渲染 → 1970-01-21。插件侧已修；宿主这层兜底保证
 * **已安装的旧 xipk 不重装也能显示正确时间**。
 */
class JsBackupTimestampNormalizeTest {

    @Test
    fun `秒级时间戳放大为毫秒`() {
        // 2025-09-13 12:26:40 UTC 左右
        assertEquals(1_757_766_400_000L, JsBackupPluginAdapter.normalizeRemoteTimestamp(1_757_766_400L))
    }

    @Test
    fun `毫秒级时间戳原样保留`() {
        assertEquals(1_757_766_400_000L, JsBackupPluginAdapter.normalizeRemoteTimestamp(1_757_766_400_000L))
        // 2000 年（9.4e11）也远大于阈值
        assertEquals(946_684_800_000L, JsBackupPluginAdapter.normalizeRemoteTimestamp(946_684_800_000L))
    }

    @Test
    fun `缺失与非法时间保持原样（UI 按未知处理）`() {
        assertEquals(0L, JsBackupPluginAdapter.normalizeRemoteTimestamp(0L))
        assertEquals(-1L, JsBackupPluginAdapter.normalizeRemoteTimestamp(-1L))
    }
}
