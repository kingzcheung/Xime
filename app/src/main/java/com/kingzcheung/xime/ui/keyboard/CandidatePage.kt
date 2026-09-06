package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CandidatePageState(
    val candidates: List<String>,
    val candidateComments: List<String> = emptyList(),
    val associationCandidates: List<String> = emptyList(),
    val backgroundColor: Color,
    val textColor: Color,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val bottomPaddingDp: Int = 0,
)

data class CandidatePageCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onPageDown: (() -> Unit)? = null,
    val onPageUp: (() -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
)

@Composable
fun CandidatePage(
    state: CandidatePageState,
    callbacks: CandidatePageCallbacks,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // 图标按钮容器色：surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    val candidateFontFamily = AppFonts.candidateFontFamily
    val commentFontFamily = AppFonts.commentFontFamily

    val centerPage = 1
    val pagerState = rememberPagerState(initialPage = centerPage, pageCount = { 3 })

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != centerPage) {
            if (pagerState.currentPage == 0 && state.hasPrevPage && callbacks.onPageUp != null) {
                callbacks.onPageUp()
            } else if (pagerState.currentPage == 2 && state.hasNextPage && callbacks.onPageDown != null) {
                callbacks.onPageDown()
            }
            pagerState.scrollToPage(centerPage)
        }
    }

    // 禁用页面滑动手势（翻页由按钮控制，避免滑到空白页）
    val userScrollEnabled = false

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(state.backgroundColor)
    ) {
        // 导航区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Spacer(modifier = Modifier.weight(1f))

            // 翻页按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.hasPrevPage && callbacks.onPageUp != null) state.textColor.copy(alpha = 0.5f)
                            else state.textColor.copy(alpha = 0.1f)
                        )
                        .clickable(
                            enabled = state.hasPrevPage && state.candidates.isNotEmpty() && callbacks.onPageUp != null,
                            onClick = { callbacks.onPageUp?.invoke() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一页",
                        tint = if (state.hasPrevPage) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.hasNextPage && callbacks.onPageDown != null) state.textColor.copy(alpha = 0.25f)
                            else state.textColor.copy(alpha = 0.1f)
                        )
                        .clickable(
                            enabled = state.hasNextPage && state.candidates.isNotEmpty() && callbacks.onPageDown != null,
                            onClick = { callbacks.onPageDown?.invoke() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一页",
                        tint = if (state.hasNextPage) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { callbacks.onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "返回",
                    tint = state.textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = userScrollEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) { page ->
            if (page == centerPage) {
                // 候选条目点击与外层 verticalScroll 存在手势竞争：按下后轻微位移超过
                // touch slop 即被判定为滚动，点击被静默取消（无任何反馈）。部分 ROM
                // （如鸿蒙）的 slop/触摸采样更敏感，表现为"偶尔点击候选无反应"。
                // 内容不超高时彻底禁用滚动容器，保证点击稳定命中；超高时仍可滚动。
                var viewportHeight by remember { mutableStateOf(0) }
                var contentHeight by remember { mutableStateOf(0) }
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { viewportHeight = it.height }
                        .verticalScroll(
                            scrollState,
                            enabled = viewportHeight > 0 && contentHeight > viewportHeight
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { contentHeight = it.height }
                    ) {
                        if (state.candidates.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.candidates.forEachIndexed { index, candidate ->
                                CandidatePageItem(
                                    text = candidate,
                                    comment = state.candidateComments.getOrElse(index) { "" },
                                    onClick = { callbacks.onCandidateSelect(index) },
                                    textColor = state.textColor,
                                    candidateFontFamily = candidateFontFamily,
                                    commentFontFamily = commentFontFamily
                                )
                            }
                        }
                    }

                    if (state.associationCandidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.associationCandidates.forEachIndexed { index, candidate ->
                                CandidatePageItem(
                                    text = candidate,
                                    comment = "",
                                    onClick = { callbacks.onAssociationSelect?.invoke(index) },
                                    textColor = state.textColor,
                                    candidateFontFamily = candidateFontFamily,
                                    commentFontFamily = commentFontFamily
                                )
                            }
                        }
                    }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 底部留空
        Spacer(
            modifier = Modifier.height(
                if (isLandscape) 15.dp else state.bottomPaddingDp.dp
            )
        )
    }
}

@Composable
fun CandidatePageItem(
    text: String,
    comment: String = "",
    onClick: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
    candidateFontFamily: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Default,
    commentFontFamily: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Default,
) {
    val displayComment = comment.replace("~", "")

    // 按压高亮：与主键盘按键同风格的按下反馈（默认 ripple 在部分主题背景上不可见）
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Row(

        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPressed) textColor.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 2.dp),
            fontFamily = candidateFontFamily
        )
        if (displayComment.isNotEmpty()) {
            Text(
                text = displayComment,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 1.dp),
                fontFamily = commentFontFamily
            )
        }
    }
}
