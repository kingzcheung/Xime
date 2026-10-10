package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 「同步与备份」卡片状态文案（相对时间）。
 *
 * P0 重设计把"上次同步：2026-10-10 12:30"换成"上次成功 今天 12:30"——时间越近越该说人话；
 * 这里锁住分段规则（今天/昨天/本年/跨年），[now] 注入保证不受运行时间影响。
 */
class BackupStatusTextTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `从未成功过返回从未`() {
        assertEquals("从未", formatRelativeTime(0L))
        assertEquals("从未", formatRelativeTime(-1L))
    }

    @Test
    fun `同一天只说今天几点`() {
        val now = at(2026, 10, 10, 20, 30)
        assertEquals("今天 12:30", formatRelativeTime(at(2026, 10, 10, 12, 30), now))
        assertEquals("今天 00:05", formatRelativeTime(at(2026, 10, 10, 0, 5), now))
    }

    @Test
    fun `前一天说昨天几点（跨月也算昨天）`() {
        val now = at(2026, 11, 1, 9, 0)
        assertEquals("昨天 23:59", formatRelativeTime(at(2026, 10, 31, 23, 59), now))
    }

    @Test
    fun `本年更早给月日时分`() {
        val now = at(2026, 10, 10, 20, 30)
        // 注意：前一天会命中"昨天"，这里取更早的日子
        assertEquals("10-05 22:31", formatRelativeTime(at(2026, 10, 5, 22, 31), now))
        assertEquals("01-01 08:00", formatRelativeTime(at(2026, 1, 1, 8, 0), now))
    }

    @Test
    fun `跨年给完整日期`() {
        val now = at(2026, 10, 10, 20, 30)
        assertEquals("2025-12-31", formatRelativeTime(at(2025, 12, 31, 22, 0), now))
    }
}
