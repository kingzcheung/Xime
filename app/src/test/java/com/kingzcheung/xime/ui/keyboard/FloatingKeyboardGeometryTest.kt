package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingKeyboardGeometryTest {

    private fun clamp(
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        screenW: Int = 400,
        screenH: Int = 800,
        cardW: Float = FloatingKeyboardGeometry.cardWidthDp(400, 800).toFloat(),
        cardH: Float = 300f,
        minY: Float = 0f,
    ) = FloatingKeyboardGeometry.clampOffset(offsetX, offsetY, screenW, screenH, cardW, cardH, minY)

    @Test
    fun `卡片宽度按屏幕短边缩放`() {
        // 竖屏：短边 = 宽
        assertEquals((400 * FloatingKeyboardGeometry.SCALE_FRACTION).roundToInt(),
            FloatingKeyboardGeometry.cardWidthDp(400, 800))
        // 横屏：短边 = 高，卡片宽度与竖屏一致
        assertEquals((400 * FloatingKeyboardGeometry.SCALE_FRACTION).roundToInt(),
            FloatingKeyboardGeometry.cardWidthDp(800, 400))
    }

    @Test
    fun `半程边距不为负`() {
        // 卡片比屏幕还宽时边距收敛为 0
        assertEquals(0, FloatingKeyboardGeometry.halfMarginDp(300, 400))
        assertEquals(0, FloatingKeyboardGeometry.halfMarginDp(400, 400))
        assertEquals(60, FloatingKeyboardGeometry.halfMarginDp(400, 280))
    }

    @Test
    fun `水平方向钳制在正负半程边距内`() {
        // 默认参数：卡片宽 340，半程边距 = (400-340)/2 = 30
        val (x, _) = clamp(offsetX = 500f)
        assertEquals(30f, x)
        val (x2, _) = clamp(offsetX = -500f)
        assertEquals(-30f, x2)
        val (x3, _) = clamp(offsetX = 30f, cardW = 280f)
        assertEquals(30f, x3)
    }

    @Test
    fun `垂直方向钳制在最小值与上界之间`() {
        // 正常上界 = screenH - cardH - EDGE_MARGIN
        val (_, y) = clamp(offsetY = 10_000f, screenH = 800, cardH = 300f)
        assertEquals(800f - 300f - FloatingKeyboardGeometry.EDGE_MARGIN_DP, y)
        // minY 高于上界时取 minY（不产生反向钳制）
        val (_, y2) = clamp(offsetY = -5f, screenH = 800, cardH = 300f, minY = 600f)
        assertEquals(600f, y2)
        // 低于 minY 时取 minY
        val (_, y3) = clamp(offsetY = -5f, minY = 16f)
        assertEquals(16f, y3)
    }

    @Test
    fun `上界不会小于 minY`() {
        // screenH - cardH - margin < minY 时上界抬升到 minY
        val (_, y) = clamp(offsetY = 0f, screenH = 100, cardH = 300f, minY = 50f)
        assertEquals(50f, y)
    }

    @Test
    fun `边界内的位置原样返回`() {
        val (x, y) = clamp(offsetX = 12f, offsetY = 40f, cardW = 280f)
        assertEquals(12f, x)
        assertEquals(40f, y)
    }

    @Test
    fun `卡片高度为0时不限制垂直上界`() {
        // 拖拽提交在卡片未完成布局时的兜底：cardH=0 → 上界只受 EDGE_MARGIN 约束
        val (_, y) = clamp(offsetY = 10_000f, screenH = 800, cardH = 0f)
        assertEquals(800f - FloatingKeyboardGeometry.EDGE_MARGIN_DP, y)
    }
}
