package com.ahu.ahutong.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.ui.shell.home.*
import com.ahu.ahutong.ui.plugin.PluginRegistry
import com.kyant.monet.n1
import com.kyant.monet.withNight

@Composable
internal fun OriginalToolsPage(onBack: () -> Unit, onNavigate: (String) -> Unit, onManage: () -> Unit) {
    val plugins by PluginRegistry.plugins.collectAsState()
    val groups = remember(plugins) { WidgetCategory.entries.mapNotNull { category ->
        HomeWidgetRegistry.availableWidgets(true).filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { category to it }
    } }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("全部小工具", style = MaterialTheme.typography.headlineMedium)
            }
        }
        groups.forEach { (category, widgets) ->
            item(span = { GridItemSpan(maxLineSpan) }) { Text(category.title, style = MaterialTheme.typography.titleSmall,
                color = 50.n1 withNight 70.n1, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(widgets, key = { it.id }) { spec -> CategorizedToolCard(spec, onClick = { onNavigate(spec.route) }) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = onManage) { Icon(Icons.Outlined.Extension, null); Text("插件管理") } }
    }
}
