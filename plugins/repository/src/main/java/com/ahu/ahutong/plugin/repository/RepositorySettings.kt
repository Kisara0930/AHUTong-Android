package com.ahu.ahutong.plugin.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ahu.ahutong.ui.components.appLiquidGlassSceneBackground
import com.ahu.ahutong.ui.components.appLiquidGlassSurface
import com.ahu.ahutong.ui.components.AppPageHeader
import com.ahu.ahutong.ui.shape.SmoothRoundedCornerShape
import com.ahu.ahutong.ui.theme.LiquidGlassSurfaceLevel
import com.kyant.monet.a1
import com.kyant.monet.n1
import com.kyant.monet.withNight

@Composable
fun RepositorySettings(
    navController: NavHostController,
    host: com.ahu.ahutong.core.plugin.PluginHostServices
) {
    var selectedSourceId by remember { mutableStateOf(host.storage().getString("repository_acceleration_source") ?: "jsdelivr") }
    val cardColor = 100.n1 withNight 20.n1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .appLiquidGlassSceneBackground(96.n1 withNight 10.n1)
    ) {
        AppPageHeader(
            title = "学习资料设置",
            onBack = { navController.popBackStack() }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "GitHub 加速源",
                modifier = Modifier.padding(horizontal = 8.dp),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .appLiquidGlassSurface(
                        shape = SmoothRoundedCornerShape(24.dp),
                        fallbackColor = cardColor,
                        level = LiquidGlassSurfaceLevel.Panel
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RepositoryManager.accelerationSources.forEach { source ->
                    RepositoryAccelerationSourceRow(
                        source = source,
                        selected = source.id == selectedSourceId,
                        onClick = {
                            host.storage().putString("repository_acceleration_source", source.id)
                            selectedSourceId = source.id
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositoryAccelerationSourceRow(
    source: RepositoryAccelerationSource,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val descriptionColor = if (isDark) {
        Color.White.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) 92.a1 withNight 32.a1 else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = source.description,
                color = descriptionColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
