package com.ahu.ahutong.ui.screen.canteen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.monet.withNight

/**
 * 必吃榜内容页的共享视觉语言（素底点评风）。
 * 足迹页与全校榜共用：橙色点睛、彩色面积克制。随应用亮暗切换。
 *
 * 中性色做成 @Composable getter（同 MaterialTheme.colorScheme 的做法），调用点无需改动；
 * 亮色分支保留原设计值逐字节不变，只补暗色分支——橙色系（点睛色/金银铜）两模式共用。
 */
internal object CanteenPalette {
    /** 品牌色：不随亮暗切换（在深色底上同样成立）。 */
    val accent = Color(0xFFFF6633)       // 点评橙：点睛色
    val accentDeep = Color(0xFFD64A17)   // 热力最高档
    val gold = Color(0xFFF5A623)
    val bronze = Color(0xFFC08A5A)

    /** 页面底 / 普通卡片底（亮色下两者同为白，关系在暗色下保持一致）。 */
    val pageBg: Color @Composable get() = Color.White withNight Color(0xFF121212)
    val cardBg: Color @Composable get() = Color.White withNight Color(0xFF121212)

    val textPrimary: Color @Composable get() = Color(0xFF1A1A1A) withNight Color(0xFFEDEAE6)
    val textSecondary: Color @Composable get() = Color(0xFF999999) withNight Color(0xFF8C8880)
    val cardLine: Color @Composable get() = Color(0xFFE8E6E0) withNight Color(0xFF33312C)

    /** 分段控件未选中底 / 名次徽章底 / 零热度格。 */
    val chip: Color @Composable get() = Color(0xFFF5F4F0) withNight Color(0xFF2A2825)
    val badgeBg: Color @Composable get() = Color(0xFFF0EFE9) withNight Color(0xFF2A2825)
    val heat0: Color @Composable get() = Color(0xFFF0EFE9) withNight Color(0xFF2A2825)
    val heat1: Color @Composable get() = Color(0xFFFFD9C2) withNight Color(0xFF5C3A26)
    val heat2: Color @Composable get() = Color(0xFFFFAF85) withNight Color(0xFF7E4A2B)

    /** 冠军卡底的暖色提示：亮色近白、暗色近黑，都带一点暖。 */
    val championBg: Color @Composable get() = Color(0xFFFFF9F5) withNight Color(0xFF2A231D)

    /** 未收录/未认领的次级文案与虚线圈。 */
    val unclaimedText: Color @Composable get() = Color(0xFF666666) withNight Color(0xFF9A958D)
    val medalRing: Color @Composable get() = Color(0xFFC8C6BE) withNight Color(0xFF3A3833)
}

internal val HeroNumber = TextStyle(fontSize = 42.sp, fontWeight = FontWeight.Medium)
internal val SectionTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
internal val BodyText = TextStyle(fontSize = 13.sp)
internal val CaptionText = TextStyle(fontSize = 11.sp)

/** 橙色竖条小节头。 */
@Composable
internal fun SectionHeader(title: String, tail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(13.dp)
                    .background(CanteenPalette.accent)
            )
            Spacer(Modifier.width(6.dp))
            Text(title, style = SectionTitle, color = CanteenPalette.textPrimary)
        }
        if (tail != null) {
            Text(tail, style = CaptionText, color = CanteenPalette.accent)
        }
    }
}

/** 描边徽章 chip（本周尝新 ×2 这类）。 */
@Composable
internal fun OutlineChip(text: String) {
    Box(
        Modifier
            .border(0.5.dp, CanteenPalette.cardLine, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = CaptionText, color = Color(0xFF666666))
    }
}

/** 分 → "¥12.30"。 */
internal fun Long.money(): String = "¥%.2f".format(this / 100.0).trimEnd('0').trimEnd('.')
