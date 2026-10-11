package com.kingzcheung.xime.clipboard

import android.content.ClipData
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.withTimeout

/**
 * 剪贴板条目的跨应用拖拽发送（issue #1062）。
 *
 * 发起：`View.startDragAndDrop` + `View.DRAG_FLAG_GLOBAL`（跨应用并授予 ClipData
 * 内 URI 的读权限）。文本走 text/plain；图片走 [ClipboardManager.dragImageUri]
 * （FileProvider 优先 → MediaStore 降级）的 content URI，目标应用（QQ/TIM 等）
 * 在 drop 时按 MIME 读取。
 *
 * 与长按菜单并存的手势语义见 [Modifier.clipboardLongPressDrag]：
 * 长按抬起未拖拽 → 菜单；长按后拖出 touch slop → 拖拽发送。
 */
object ClipboardDragSender {

    /** 拖影尺寸（dp，绘制时按发起 View 的 density 换算 px）。 */
    internal const val SHADOW_TEXT_W_DP = 160
    internal const val SHADOW_TEXT_H_DP = 48
    internal const val SHADOW_IMAGE_DP = 72

    /**
     * 发起拖拽发送。
     *
     * @param imageFile 图片条目的本地文件（缩略图拖影用；文本条目传 null）
     * @return 是否成功启动；失败（URI 获取失败/系统拒绝）时调用方回退长按菜单
     */
    fun startDragAndDrop(view: View, item: ClipboardItem, imageFile: File?): Boolean {
        return try {
            val density = view.resources.displayMetrics.density
            val (clip, shadow) = if (item.isImage) {
                val uri: Uri = ClipboardManager.getInstance(view.context).dragImageUri(item)
                    ?: return false
                ClipData.newUri(view.context.contentResolver, dragLabel(item), uri) to
                    ClipboardDragShadowBuilder(view, item, imageFile, density)
            } else {
                ClipData.newPlainText(dragLabel(item), item.text) to
                    ClipboardDragShadowBuilder(view, item, null, density)
            }
            view.startDragAndDrop(clip, shadow, null, View.DRAG_FLAG_GLOBAL)
        } catch (e: Exception) {
            android.util.Log.w("ClipboardDrag", "startDragAndDrop failed", e)
            false
        }
    }

    /** 拖拽/系统剪贴板的描述标签：文本取首行前 24 字符，图片固定文案。 */
    internal fun dragLabel(item: ClipboardItem): String {
        if (item.isImage) {
            val size = if (item.width > 0 && item.height > 0) " · ${item.width}×${item.height}" else ""
            return "图片$size"
        }
        return item.text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.take(24)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "文本"
    }
}

/**
 * 剪贴板条目的"长按菜单 / 拖拽发送"二分手势（issue #1062 语义）。
 *
 * **必须放在 combinedClickable 之后（链上更靠后 = 更内层）**：Main pass 从内向外
 * 分发，combinedClickable 在长按触发后会消费后续所有事件（foundation
 * Clickable.kt 的 longPressTriggered 分支），若本手势在其外层收到的 move 全是
 * 已消费的（positionChange 恒为 Zero），拖拽位移永远累计不起来。
 *
 * 语义：
 * - 长按窗口内快速抬起 → 点击（由并存的 combinedClickable 处理）
 * - 长按窗口内被父层手势接管（列表滚动/翻页消费事件，Final pass 复检）→ 取消
 * - 长按达成后抬起未拖 → [onLongPress]（现有长按菜单）
 * - 长按达成后拖出 [DRAG_START_THRESHOLD_DP] → [onStartDrag]（返回 true = 系统接管，
 *   手势结束）；阈值显著大于 touch slop。位移用**绝对位置差**计算，不受事件消费
 *   状态影响。达成后本手势消费 move（内层先手），父层滚动不会抢走拖拽
 * - [onStartDrag] 返回 false → 保持待拖态不再重试，抬起仍弹菜单（降级）
 *
 * 长按触觉反馈由并存的 combinedClickable 提供（其 onLongClick 需传空回调：
 * foundation 不传 onLongClick 时长按计时器不启动、抬起会误触发点击，见
 * ClipboardView 调用处）。
 * [enabled] 为 false（多选态）时整个手势不生效。
 */
fun Modifier.clipboardLongPressDrag(
    key: Any?,
    enabled: Boolean,
    onLongPress: () -> Unit,
    onStartDrag: () -> Boolean,
): Modifier = composed {
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)
    val dragThresholdPx = with(LocalDensity.current) { DRAG_START_THRESHOLD_DP.dp.toPx() }
    pointerInput(key, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // ── 阶段 1：长按窗口（对齐官方 waitForLongPress 语义）──
            // 抬起 = 点击路径；被父层手势接管（滚动/翻页，Final 复检可见）= 取消；
            // 移动不取消；深按压提前达成（对齐系统长按）；超时 = 长按达成。
            // Final 复检跳过 down 帧：combinedClickable（外层）的正常流程会消费 down。
            var longPressAchieved = false
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    var trackingId = down.id
                    var firstEvent = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == trackingId } ?: continue
                        if (change.changedToUp()) return@withTimeout
                        if (change.isConsumed) return@withTimeout
                        if (!firstEvent) {
                            val finalCheck = awaitPointerEvent(PointerEventPass.Final)
                            if (finalCheck.changes.any { it.isConsumed }) return@withTimeout
                        }
                        firstEvent = false
                        trackingId = change.id
                    }
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                longPressAchieved = true
            }
            if (!longPressAchieved) return@awaitEachGesture
            // 长按触觉反馈由 combinedClickable 的空 onLongClick 路径发（与本手势超时同步）
            // ── 阶段 2：待拖拽。抬起 = 菜单；拖出阈值 = 拖拽 ──
            // 本手势（内层）先收并消费 move，父层滚动无法接管，无需消费检测；
            // 位移用绝对位置差，与 combinedClickable 的事件消费解耦
            var dragStarted = false
            var dragFailed = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (!dragStarted && !dragFailed) currentOnLongPress()
                    break
                }
                val totalX = change.position.x - down.position.x
                val totalY = change.position.y - down.position.y
                if (!dragStarted && !dragFailed &&
                    (abs(totalX) > dragThresholdPx || abs(totalY) > dragThresholdPx)
                ) {
                    dragStarted = currentOnStartDrag()
                    if (!dragStarted) dragFailed = true
                }
                change.consume()
                if (dragStarted) break // 系统接管：MotionEvent 停止，后续由 drag 流程处理
            }
        }
    }
}

/** 拖拽发起的位移阈值（dp）：长按后需明显拖出才启动，避免无意微动误触发。 */
private const val DRAG_START_THRESHOLD_DP = 24f

/**
 * 手绘拖影：文本 = 圆角卡 + 多行预览；图片 = 圆角缩略图（按需降采样解码）。
 * 触摸点居中（拖影中心跟随手指）。
 */
private class ClipboardDragShadowBuilder(
    view: View,
    item: ClipboardItem,
    imageFile: File?,
    private val density: Float,
) : View.DragShadowBuilder(view) {

    private val darkMode = (view.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val textPreview: String? = if (item.isImage) null else item.text

    private val cardWidth: Int
    private val cardHeight: Int
    private val thumb: Bitmap?

    init {
        if (item.isImage) {
            val side = (ClipboardDragSender.SHADOW_IMAGE_DP * density).toInt()
            cardWidth = side
            cardHeight = side
            thumb = imageFile?.let { decodeThumbnail(it, side) }
        } else {
            cardWidth = (ClipboardDragSender.SHADOW_TEXT_W_DP * density).toInt()
            cardHeight = (ClipboardDragSender.SHADOW_TEXT_H_DP * density).toInt()
            thumb = null
        }
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(cardWidth, cardHeight)
        outShadowTouchPoint.set(cardWidth / 2, cardHeight / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val radius = 12f * density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkMode) Color.rgb(44, 44, 46) else Color.WHITE
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat()), radius, radius, fill
        )
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = if (darkMode) Color.rgb(70, 70, 74) else Color.rgb(224, 224, 224)
        }
        canvas.drawRoundRect(
            RectF(stroke.strokeWidth / 2, stroke.strokeWidth / 2,
                cardWidth - stroke.strokeWidth / 2, cardHeight - stroke.strokeWidth / 2),
            radius, radius, stroke
        )

        if (thumb != null) {
            canvas.drawBitmap(
                thumb,
                android.graphics.Rect(0, 0, thumb.width, thumb.height),
                RectF(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat()), null
            )
            return
        }

        val text = textPreview ?: return
        val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkMode) Color.WHITE else Color.DKGRAY
            textSize = 14f * density
            typeface = Typeface.DEFAULT
        }
        val padding = 10f * density
        val maxW = cardWidth - padding * 2
        var y = padding + linePaint.textSize * 0.9f
        var remaining = text.trim()
        while (y <= cardHeight - padding && remaining.isNotEmpty()) {
            val line = android.text.TextUtils.ellipsize(
                remaining, linePaint, maxW, android.text.TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(line, padding, y, linePaint)
            if (line.length >= remaining.length) break
            remaining = remaining.drop(line.length).trimStart()
            y += linePaint.textSize * 1.3f
        }
    }

    /** 目标边长内的降采样解码（bounds 快读 + inSampleSize），失败返回 null（画纯色卡）。 */
    private fun decodeThumbnail(file: File, targetPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx &&
                bounds.outHeight / (sample * 2) >= targetPx
            ) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    } catch (e: Exception) {
        null
    }
}
