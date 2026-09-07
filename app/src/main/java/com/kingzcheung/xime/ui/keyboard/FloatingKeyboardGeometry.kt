package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt

/**
 * 悬浮键盘几何常量与边界计算。
 *
 * 服务层（拖拽提交/启动恢复时 clamp）、模式切换（ImeSchemaController）与
 * Compose 容器（FloatingKeyboardContainer 拖拽实时 clamp）共用同一份公式，
 * 避免魔数散落与两套 clamp 规则不一致导致的位置跳变。纯 Kotlin，可单元测试。
 */
object FloatingKeyboardGeometry {

    /** 悬浮卡片宽度与键盘内容缩放比例：卡片宽/键盘高 = 短边 × 此比例 */
    const val SCALE_FRACTION = 0.85f

    /** 卡片顶部拖拽条高度（dp），同时计入卡片总高 */
    const val DRAG_BAR_HEIGHT_DP = 18

    /** 悬浮卡片上/下边缘与屏幕可用区域的保留间距（dp） */
    const val EDGE_MARGIN_DP = 20

    /** 悬浮模式下 currentEffectiveKeyboardHeight 估算时的额外高度（候选栏等，dp） */
    const val EXTRA_HEIGHT_ESTIMATE_DP = 50

    /** 卡片宽度：以屏幕短边为基准缩放，横竖屏下卡片视觉宽度一致 */
    fun cardWidthDp(screenWidthDp: Int, screenHeightDp: Int): Int =
        (minOf(screenWidthDp, screenHeightDp) * SCALE_FRACTION).roundToInt()

    /** 水平方向可移动半程：卡片中心相对屏幕中心的左右边界 */
    fun halfMarginDp(screenWidthDp: Int, cardWidthDp: Int): Int =
        maxOf(0, (screenWidthDp - cardWidthDp) / 2)

    /**
     * 统一的悬浮位置 clamp：
     * - 水平：[-halfMargin, halfMargin]
     * - 垂直：[minY, screenH - cardH - EDGE_MARGIN_DP]（offsetY 为卡片距底部的上移量）
     */
    fun clampOffset(
        offsetX: Float,
        offsetY: Float,
        screenWidthDp: Int,
        screenHeightDp: Int,
        cardWidthDp: Float,
        cardHeightDp: Float,
        minY: Float,
    ): Pair<Float, Float> {
        val halfMargin = maxOf(0f, (screenWidthDp - cardWidthDp) / 2f)
        val clampedX = offsetX.coerceIn(-halfMargin, halfMargin)
        val maxY = maxOf(minY, screenHeightDp - cardHeightDp - EDGE_MARGIN_DP)
        val clampedY = offsetY.coerceIn(minY, maxY)
        return clampedX to clampedY
    }
}
