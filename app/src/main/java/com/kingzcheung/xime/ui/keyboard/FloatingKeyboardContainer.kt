package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun FloatingKeyboardContainer(
    isFloatingMode: Boolean,
    scaleFactor: Float,
    fontScaleFactor: Float = scaleFactor,
    offsetX: Int,
    offsetY: Int,
    minOffsetY: Int = 0,
    maxOffsetY: Int = Int.MAX_VALUE,
    backgroundColor: Color = Color.Transparent,
    onDragCommit: (x: Float, y: Float) -> Unit,
    onCardPositioned: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _: Int, _: Int, _: Int, _: Int -> },
    keyboardContent: @Composable () -> Unit,
) {
    if (!isFloatingMode) {
        keyboardContent()
        return
    }

    val density = LocalDensity.current
    // 拖拽位移用容器本地状态承载（clamp 后的绝对位置，dp），dragEnd 才一次性
    // 提交到服务层 uiState。若每帧都写 uiState，kbState（remember key 含整体
    // state）会随每个 pointer-move 重建并触发整个键盘重组。
    var cardX by remember { mutableFloatStateOf(offsetX.toFloat()) }
    var cardY by remember { mutableFloatStateOf(offsetY.toFloat()) }
    // 提交后服务层会回写 clamp 后的 offsetX/offsetY，本地状态同步对齐；
    // 也覆盖模式切换/恢复已保存位置等 props 主动变化的场景。
    LaunchedEffect(offsetX, offsetY) {
        cardX = offsetX.toFloat()
        cardY = offsetY.toFloat()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardTotalHeight = maxHeight
        val halfMarginX = (maxWidth.value * (1f - scaleFactor) / 2f)
        Box(
            modifier = Modifier
                .fillMaxWidth(scaleFactor)
                .height(cardTotalHeight)
                .offset(x = cardX.dp, y = (-cardY).dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    val size = coords.size
                    onCardPositioned(
                        pos.x.roundToInt(),
                        pos.y.roundToInt(),
                        (pos.x + size.width).roundToInt(),
                        (pos.y + size.height).roundToInt()
                    )
                }
        ) {
            Column {
                DragBar(
                    backgroundColor = backgroundColor,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp().value }
                        // y 轴翻转：offsetY 语义是"卡片距底部的上移量"，向上拖为正
                        val dyUpDp = with(density) { (-dragAmount.y).toDp().value }
                        cardX = (cardX + dxDp).coerceIn(-halfMarginX, halfMarginX)
                        cardY = (cardY + dyUpDp).coerceIn(minOffsetY.toFloat(), maxOffsetY.toFloat())
                    },
                    onDragEnd = { onDragCommit(cardX, cardY) },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(
                        // 字体缩放走 LocalDensity.fontScale 覆写（只影响 sp 文本），
                        // 与之并行的另一条缩放轨道是：键盘内容高度由服务层预乘
                        // FloatingKeyboardGeometry.SCALE_FRACTION 后以 dp 传入。
                        // 两轨道需使用同一比例，改动时务必同步（见 KeyboardView.floatFontScale）。
                        LocalDensity provides Density(density = density.density, fontScale = density.fontScale * fontScaleFactor)
                    ) {
                        keyboardContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun DragBar(
    backgroundColor: Color,
    onDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    // 手柄颜色随卡片背景明暗取反色，浅色主题下保持可见
    val handleColor = if (backgroundColor.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.35f)
    } else {
        Color.White.copy(alpha = 0.6f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FloatingKeyboardGeometry.DRAG_BAR_HEIGHT_DP.dp)
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = onDrag,
                    onDragEnd = onDragEnd
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(handleColor)
        )
    }
}
