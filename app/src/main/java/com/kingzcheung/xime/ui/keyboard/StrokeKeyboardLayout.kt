package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 笔画键盘布局 — 参照 [T9KeyboardLayout] 结构。
 *
 * 布局说明：
 * - 左侧候选区：。？ ！ ~ 4 个常用标点，纵向排列。
 * - 中间主键区：
 *   第1行：一(h) | 丨(s) | 丿(p)
 *   第2行：丶(n) | 乛(z) | *
 *   第3行：分词 | ， | 英
 *   第4行：符号 | 123 | 空格 | .
 * - 右侧功能键区：退格 | 重输 | 确定
 */
@Composable
fun StrokeKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
) {
    StrokeKeyboardSwipeOverlay(
        modifier = modifier,
        keyboardBackgroundColor = keyboardBackgroundColor,
        keyCornerRadius = keyCornerRadius,
        keyTextColor = keyTextColor,
        isFloatingMode = isFloatingMode,
        onKeyPress = onKeyPress,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        bubbleBackgroundColor = bubbleBackgroundColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPressDown = onKeyPressDown,
        specialKeyTextColor = specialKeyTextColor,
        keySpacingX = keySpacingX,
        keySpacingY = keySpacingY,
    )
}

// ─── 滑动气泡覆盖层 ────────────────────────────────────────────────

@Composable
private fun StrokeKeyboardSwipeOverlay(
    modifier: Modifier,
    keyboardBackgroundColor: Color,
    keyCornerRadius: Dp,
    keyTextColor: Color,
    isFloatingMode: Boolean,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
onKeyPressDown: ((String) -> Unit)?,
    specialKeyTextColor: Color,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp),
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(vertical = 2.dp, horizontal = 50.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                ) {
                    StrokeLandscapeSymbolPanel(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                    )
                }
                Spacer(modifier = Modifier.weight(0.16f))
                Box(
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalKeyVisualPadding provides PaddingValues(
                            horizontal = keySpacingX ?: 2.dp,
                            vertical = keySpacingY ?: 2.dp,
                        )
                    ) {
                        StrokeKeyboardContent(
                            onKeyPress = onKeyPress,
                            keyBackgroundColor = keyBackgroundColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBackgroundColor,
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            onKeyPressDown = onKeyPressDown,
                            onSwipeStateChange = ::processSwipeState,
                            specialKeyTextColor = specialKeyTextColor,
                            compactMode = true,
                        )
                    }
                }
            }
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StrokeKeyboardContent(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        specialKeyTextColor = specialKeyTextColor,
                        compactMode = false,
                    )
                }
            }
        }
    }
    }
}

// ─── 横屏符号面板 ──────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrokeLandscapeSymbolPanel(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
) {
    val density = LocalDensity.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, keyBackgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(keyBackgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    val commonSymbols = listOf(
        "~", "!", "#", "$", "%", "^", "&", "*",
        "(", ")", "_", "=", "[", "]", "{", "}",
        "\\", "|", ";", ":", "'", "\"", "<", ">"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(shadowModifier)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(keyBackgroundColor)
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                commonSymbols.forEach { sym ->
                    Box(
                        modifier = Modifier
                            .clickable { onKeyPress(sym) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sym,
                            color = keyTextColor,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

// ─── 笔画键盘三列主体 ──────────────────────────────────────────────

private data class StrokeKeyDef(
    val mainLabel: String,
    val swipeDigit: String,
    val commit: String,
)

private val strokeKeys = listOf(
    StrokeKeyDef("一", "1", "h"),
    StrokeKeyDef("丨", "2", "s"),
    StrokeKeyDef("丿", "3", "p"),
    StrokeKeyDef("丶", "4", "n"),
    StrokeKeyDef("乛", "5", "z"),
)

@Composable
private fun StrokeKeyboardContent(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    specialKeyTextColor: Color,
    compactMode: Boolean = false,
) {
    val ctrlFontSize = if (compactMode) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val strokeFontSize = if (compactMode) 13.sp else 16.sp
    val specialCtxTextColor = if (compactMode) specialKeyTextColor
        else (if (keyTextColor == Color(0xFFE8EAED)) Color.White
              else Color(0xFF1A73E8))

    val symbols = listOf("。", "？", "！", "~")
    val suppressCursorMove = LocalSuppressCursorMove.current

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
    ) {
        // ── 第1列：左侧符号区（3 行符号 + 1 行符号按钮） ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.9f),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(3f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                symbols.forEachIndexed { index, symbol ->
                    StrokeSymbolItem(
                        text = symbol,
                        isFirst = index == 0,
                        isLast = index == symbols.lastIndex,
                        onClick = { onKeyPress(symbol) },
                        onPress = { onKeyPressDown?.invoke(symbol) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
            StrokeSymbolButton(
                text = "符号",
                onClick = { onKeyPress("symbol") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onPress = { onKeyPressDown?.invoke("symbol") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = ctrlFontSize,
            )
        }

        // ── 第2列：笔画键区 ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(3.2f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                strokeKeys.take(3).forEach { key ->
                    StrokeKeyItem(
                        mainLabel = key.mainLabel,
                        swipeDigit = key.swipeDigit,
                        onClick = { onKeyPress(key.commit) },
                        onSwipeUp = { onKeyPress(key.swipeDigit) },
                        onPress = { onKeyPressDown?.invoke(key.commit) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        strokeFontSize = strokeFontSize,
                        compactMode = compactMode,
                        onSwipeStateChange = onSwipeStateChange,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                strokeKeys.drop(3).forEach { key ->
                    StrokeKeyItem(
                        mainLabel = key.mainLabel,
                        swipeDigit = key.swipeDigit,
                        onClick = { onKeyPress(key.commit) },
                        onSwipeUp = { onKeyPress(key.swipeDigit) },
                        onPress = { onKeyPressDown?.invoke(key.commit) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        strokeFontSize = strokeFontSize,
                        compactMode = compactMode,
                        onSwipeStateChange = onSwipeStateChange,
                    )
                }
                StrokeDigitKey(
                    digit = "*", swipeDigit = "6",
                    onClick = { onKeyPress("*") },
                    onSwipeUp = { onKeyPress("6") },
                    onPress = { onKeyPressDown?.invoke("*") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                StrokeDigitKey(
                    digit = "分词", swipeDigit = "7",
                    onClick = { onKeyPress("word_separator") },
                    onSwipeUp = { onKeyPress("7") },
                    onPress = { onKeyPressDown?.invoke("word_separator") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                )
                StrokeDigitKey(
                    digit = "，", swipeDigit = "8",
                    onClick = { onKeyPress("，") },
                    onSwipeUp = { onKeyPress("8") },
                    onPress = { onKeyPressDown?.invoke("，") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                )
                StrokeDigitKey(
                    digit = "英", swipeDigit = "9",
                    onClick = { onKeyPress("ime_switch") },
                    onSwipeUp = { onKeyPress("9") },
                    onPress = { onKeyPressDown?.invoke("ime_switch") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                StrokeSymbolButton(
                    text = "123",
                    onClick = { onKeyPress("number") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("number") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = ctrlFontSize,
                )
                StrokeSpaceButton(
                    onKeyPress = onKeyPress,
                    onKeyPressDown = onKeyPressDown,
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.8f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                StrokeSymbolButton(
                    text = ".",
                    onClick = { onKeyPress(".") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke(".") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = ctrlFontSize,
                )
            }
        }

        // ── 第3列：功能键区 ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.9f),
        ) {
            SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onKeyPress("delete") },
                onLongClick = { onKeyPress("delete") },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = if (compactMode) null else "清空",
                onSwipe = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("delete") },
                swipeUpLabel = if (compactMode) null else "上滑清空",
                swipeDownLabel = if (compactMode) null else "下滑撤回",
                onSwipeUp = { onKeyPress("clear_all") },
                onSwipeDown = { onKeyPress("undo_clear") },
                onSwipeLeft = {
                    suppressCursorMove.value = true
                    onKeyPress("clear_composition")
                },
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            ResetKey(
                onClick = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("clear") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                compactMode = compactMode,
            )
            StrokeSymbolButton(
                text = "确定",
                onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(2f),
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
            )
        }
    }
}

// ─── 子组件 ───────────────────────────────────────────────────────

@Composable
private fun StrokeSymbolItem(
    text: String,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) cornerRadius else 0.dp,
        topEnd = if (isFirst) cornerRadius else 0.dp,
        bottomStart = if (isLast) cornerRadius else 0.dp,
        bottomEnd = if (isLast) cornerRadius else 0.dp
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun StrokeKeyItem(
    mainLabel: String,
    swipeDigit: String,
    onClick: () -> Unit,
    onSwipeUp: (() -> Unit)?,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    strokeFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    compactMode: Boolean = false,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    SwipeableKeyButton(
        text = mainLabel,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = strokeFontSize,
        modifier = modifier,
        onPress = onPress,
        onSwipe = { onSwipeUp?.invoke() },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = swipeDigit,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        longPressItems = null,
    )
}

@Composable
private fun StrokeDigitKey(
    digit: String,
    swipeDigit: String,
    onClick: () -> Unit,
    onSwipeUp: (() -> Unit)?,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    SwipeableKeyButton(
        text = digit,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        onSwipe = { onSwipeUp?.invoke() },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = swipeDigit,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

@Composable
private fun StrokeSymbolButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    KeyButton(
        text = text,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        fontSize = fontSize,
    )
}

@Composable
private fun ResetKey(
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    compactMode: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(shadowShapeRadius)
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .then(shadowModifier)
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "重输",
            tint = textColor,
            modifier = Modifier.size(if (compactMode) 16.dp else 20.dp)
        )
        if (!compactMode) {
Text(
            text = "重输",
            color = textColor.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.offset(y = (-14).dp),
            fontFamily = AppFonts.keyFontFamily
        )
        }
    }
}

@Composable
private fun StrokeSpaceButton(
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    val density = LocalDensity.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onKeyPressDown?.invoke("space")
                        tryAwaitRelease()
                    },
                    onTap = { onKeyPress("space") },
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(shadowModifier)
                .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(backgroundColor)
        )
        Text(
            text = "空格",
            color = textColor.copy(alpha = 0.3f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontFamily = AppFonts.keyFontFamily
        )
    }
}
