package com.ahu.ahutong.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ahu.ahutong.ui.shape.SmoothRoundedCornerShape

/** 弹窗底部胶囊按钮的风格。 */
enum class AppDialogActionStyle { Neutral, Primary, Danger }

/** 弹窗底部动作：文案 + 回调 + 风格。点击不自动关闭弹窗（校验失败等场景由回调自行决定）。 */
data class AppDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val style: AppDialogActionStyle = AppDialogActionStyle.Neutral,
    val enabled: Boolean = true
)

/**
 * AppDialog：统一弹窗骨架（P0 组件，蓝本：学小通 AddEvent/WorkDetail/Remind 三弹窗）。
 *
 * 结构：Dialog → 32dp 平滑圆角容器 → 头部（标题/副标题 + 可选头部槽）→ 分割线 →
 * 内容区（可选滚动）→ [actions] 非空时追加分割线 + 等宽胶囊按钮行。
 * [content] 传 null 时整块内容区（含其 padding）不渲染。
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    titleFontWeight: FontWeight? = FontWeight.SemiBold,
    titleMaxLines: Int = Int.MAX_VALUE,
    contentScrollable: Boolean = false,
    contentSpacing: Dp = 14.dp,
    properties: DialogProperties = DialogProperties(),
    actions: List<AppDialogAction> = emptyList(),
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss, properties = properties) {
        Column(
            modifier = modifier
                .clip(SmoothRoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    fontWeight = titleFontWeight,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                headerContent?.invoke(this)
            }

            AppDialogDivider()

            if (content != null) {
                Column(
                    modifier = (if (contentScrollable) Modifier.weight(1f, fill = false) else Modifier)
                        .padding(24.dp)
                        .then(
                            if (contentScrollable) {
                                Modifier.verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                    content = content
                )
            }

            if (actions.isNotEmpty()) {
                AppDialogDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    actions.forEach { AppDialogCapsuleButton(it) }
                }
            }
        }
    }
}

@Composable
private fun AppDialogDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun RowScope.AppDialogCapsuleButton(action: AppDialogAction) {
    val scheme = MaterialTheme.colorScheme
    val (container, labelColor) = when (action.style) {
        AppDialogActionStyle.Neutral -> scheme.onSurface.copy(alpha = 0.08f) to scheme.onSurface
        AppDialogActionStyle.Primary -> scheme.primary.copy(alpha = 0.15f) to scheme.primary
        AppDialogActionStyle.Danger -> scheme.error.copy(alpha = 0.15f) to scheme.error
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (action.enabled) container else scheme.onSurface.copy(alpha = 0.05f))
            .clickable(enabled = action.enabled, onClick = action.onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = action.label,
            fontSize = 13.sp,
            color = if (action.enabled) labelColor else scheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium
        )
    }
}
