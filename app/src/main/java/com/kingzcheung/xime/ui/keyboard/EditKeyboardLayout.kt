package com.kingzcheung.xime.ui.keyboard

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val keyShape = RoundedCornerShape(8.dp)

private data class Quadrant(
    val label: String,
    val startAngle: Float,
    val actionBase: String
)

private val quadrants = listOf(
    Quadrant("↑", 225f, "arrow_up"),
    Quadrant("→", 315f, "arrow_right"),
    Quadrant("↓", 45f, "arrow_down"),
    Quadrant("←", 135f, "arrow_left"),
)

@Composable
fun EditKeyboardLayout(
    onAction: (String) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val keyBg = keyBgColor
    var isSelecting by remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sideGap = if (isLandscape) 50.dp else 8.dp

    fun arrowAction(base: String): String = if (isSelecting) "select_$base" else base

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = sideGap, end = sideGap),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(keyBg)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "返回",
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = sideGap)
        ) {
            SideButtonGrid(
                items = listOf(
                    "复制" to "copy", "粘贴" to "paste",
                    "全选" to "select_all", "剪切" to "cut"
                ),
                columns = if (isLandscape) 2 else 1,
                onAction = onAction,
                keyBg = keyBg,
                textColor = textColor,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val circleModifier = if (isLandscape) {
                    Modifier.fillMaxHeight(1f).aspectRatio(1f)
                } else {
                    Modifier.fillMaxWidth(0.85f).aspectRatio(1f)
                }
                CircularDPad(
                    isSelecting = isSelecting,
                    onDirection = { dir -> onAction(arrowAction(dir)) },
                    onToggleSelect = {
                        val newSelecting = !isSelecting
                        isSelecting = newSelecting
                        onAction(if (newSelecting) "select_begin" else "select_end")
                    },
                    keyBg = keyBg,
                    textColor = textColor,
                    accentColor = accentColor,
                    backgroundColor = backgroundColor,
                    modifier = circleModifier
                )
            }

            SideButtonGrid(
                items = listOf(
                    "段首" to "home", "段尾" to "end",
                    "删除" to "delete", "回车" to "enter"
                ),
                columns = if (isLandscape) 2 else 1,
                onAction = onAction,
                keyBg = keyBg,
                textColor = textColor,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(
            modifier = Modifier.height(bottomPaddingDp.dp)
        )
    }
}

@Composable
private fun CircularDPad(
    isSelecting: Boolean,
    onDirection: (String) -> Unit,
    onToggleSelect: () -> Unit,
    keyBg: Color,
    textColor: Color,
    accentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    val outerFraction = 0.92f
    val innerFraction = 0.38f

    var pressedAction by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(keyBg)
            .pointerInput(isSelecting) {
                detectTapGestures(
                    onPress = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val cx = w / 2f
                        val cy = h / 2f
                        val dist = sqrt((offset.x - cx) * (offset.x - cx) + (offset.y - cy) * (offset.y - cy))
                        val outerR = w / 2f * outerFraction
                        val innerR = w / 2f * innerFraction
                        when {
                            dist < innerR -> pressedAction = "center"
                            dist < outerR -> {
                                val angle = atan2(offset.y - cy, offset.x - cx)
                                val deg = ((Math.toDegrees(angle.toDouble()) + 360) % 360).toFloat()
                                pressedAction = when {
                                    deg in 225f..314f -> quadrants[0].actionBase
                                    deg in 315f..359f || deg in 0f..44f -> quadrants[1].actionBase
                                    deg in 45f..134f -> quadrants[2].actionBase
                                    else -> quadrants[3].actionBase
                                }
                            }
                            else -> pressedAction = null
                        }
                        tryAwaitRelease()
                        pressedAction = null
                    },
                    onTap = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val cx = w / 2f
                        val cy = h / 2f
                        val dist = sqrt((offset.x - cx) * (offset.x - cx) + (offset.y - cy) * (offset.y - cy))
                        val outerR = w / 2f * outerFraction
                        val innerR = w / 2f * innerFraction
                        when {
                            dist < innerR -> onToggleSelect()
                            dist < outerR -> {
                                val angle = atan2(offset.y - cy, offset.x - cx)
                                val deg = ((Math.toDegrees(angle.toDouble()) + 360) % 360).toFloat()
                                val dirAction = when {
                                    deg in 225f..314f -> quadrants[0].actionBase
                                    deg in 315f..359f || deg in 0f..44f -> quadrants[1].actionBase
                                    deg in 45f..134f -> quadrants[2].actionBase
                                    else -> quadrants[3].actionBase
                                }
                                onDirection(dirAction)
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val outerR = w / 2f * outerFraction
            val innerR = w / 2f * innerFraction

            for (q in quadrants) {
                drawArc(
                    color = keyBg,
                    startAngle = q.startAngle,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            drawCircle(color = backgroundColor, radius = innerR)

            val centerR = innerR * 0.82f
            if (isSelecting) {
                drawCircle(color = accentColor.copy(alpha = 0.25f), radius = centerR)
            }
            drawCircle(color = keyBg, radius = centerR)
            drawCircle(
                color = textColor.copy(alpha = 0.15f),
                radius = centerR,
                style = Stroke(width = 1.dp.toPx())
            )

            // 按下效果：外圆向内圆方向渐变
            val pressedIdx = quadrants.indexOfFirst { it.actionBase == pressedAction }
            if (pressedIdx >= 0) {
                val q = quadrants[pressedIdx]
                val gradient = Brush.radialGradient(
                    0f to Color.Transparent,
                    (innerR / outerR) to Color.Transparent,
                    1f to accentColor.copy(alpha = 0.3f),
                    center = Offset(cx, cy),
                    radius = outerR
                )
                drawArc(
                    brush = gradient,
                    startAngle = q.startAngle,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            directionLabelPaint.textSize = 28.sp.toPx()
            directionLabelPaint.color = textColor.toArgb()
            val labelR = (innerR + outerR) / 2f
            for (q in quadrants) {
                val midDeg = Math.toRadians((q.startAngle + 45f).toDouble()).toFloat()
                val lx = cx + labelR * cos(midDeg)
                val ly = cy + labelR * sin(midDeg)
                drawContext.canvas.nativeCanvas.drawText(
                    q.label, lx, ly + directionLabelPaint.textSize * 0.35f, directionLabelPaint
                )
            }

            val centerLabel = if (isSelecting) "取消" else "选择"
            centerLabelPaint.textSize = 13.sp.toPx()
            centerLabelPaint.color = if (isSelecting) accentColor.toArgb() else textColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                centerLabel, cx, cy + centerLabelPaint.textSize * 0.35f, centerLabelPaint
            )
        }
    }
}

private val directionLabelPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = AppFonts.keyFontTypeface
}

private val centerLabelPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = AppFonts.keyFontTypeface
}

@Composable
private fun SideButtonGrid(
    items: List<Pair<String, String>>,
    columns: Int,
    onAction: (String) -> Unit,
    keyBg: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val rows = items.chunked(columns)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (label, action) ->
                    SideButton(label, { onAction(action) }, keyBg, textColor, Modifier.weight(1f).fillMaxHeight())
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    onClick: () -> Unit,
    keyBg: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(keyShape)
            .background(keyBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontFamily = AppFonts.keyFontFamily
        )
    }
}


