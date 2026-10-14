package com.ahu.ahutong.ui.shell.home
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.ui.shape.SmoothRoundedCornerShape
import com.kyant.monet.n1
import com.kyant.monet.withNight
@Composable
internal fun CategorizedToolCard(
    spec: HomeWidgetSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .border(
                width = 1.dp,
                color = 90.n1 withNight 30.n1,
                shape = SmoothRoundedCornerShape(16.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val iconBytes = spec.iconBytes
        val bitmap = if (iconBytes != null) {
            remember(iconBytes) {
                android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                    ?.asImageBitmap()
            }
        } else {
            null
        }
        when {
            bitmap != null -> androidx.compose.foundation.Image(
                bitmap = bitmap,
                modifier = Modifier.size(20.dp),
                contentDescription = null
            )
            // iconId=0 是插件没带资源图标的形态（iconBytes 优先，双缺失时兜底）
            spec.iconId != 0 -> Icon(
                painter = painterResource(id = spec.iconId),
                modifier = Modifier.size(20.dp),
                contentDescription = null,
                tint = spec.tint
            )
            else -> Icon(
                painter = painterResource(id = com.ahu.ahutong.R.drawable.ic_round_business_24),
                modifier = Modifier.size(20.dp),
                contentDescription = null,
                tint = spec.tint
            )
        }
        Text(
            text = spec.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}