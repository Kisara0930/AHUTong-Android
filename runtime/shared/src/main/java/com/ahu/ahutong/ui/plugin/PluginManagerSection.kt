package com.ahu.ahutong.ui.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.ui.components.AppButton
import com.ahu.ahutong.ui.components.AppButtonVariant
import com.ahu.ahutong.ui.components.AppDialog
import com.ahu.ahutong.ui.components.AppDialogAction
import com.ahu.ahutong.ui.components.AppDialogActionStyle
import com.ahu.ahutong.ui.components.AppSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local signed-package import remains available when the market is offline. */
@Composable
fun PluginManagerSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<AhupCandidate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var listTick by remember { mutableIntStateOf(0) }
    var uninstallId by remember { mutableStateOf<String?>(null) }

    fun perform(action: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onFailure { error = it.message ?: "操作失败" }
            PluginRegistry.reload(context)
            listTick++
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { AhupInstaller.inspect(context, uri) } }
                    .onSuccess { candidate = it; error = null }
                    .onFailure { error = it.message ?: "插件包验证失败" }
                busy = false
            }
        }
    }

    val runtimePlugins = remember(listTick) { AhupInstaller.installedIds(context) }
    val required = remember(listTick) { AhupGate.requiredIds(context) }
    AppSectionCard(modifier = Modifier) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("功能插件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("只接受官方签名插件。停用或卸载保留数据；已加载版本的更新在重启应用后生效。",
                style = MaterialTheme.typography.bodySmall)
            runtimePlugins.forEach { id ->
                val manifest = AhupInstaller.manifestOf(context, id)
                val enabled = AhupInstaller.isEnabled(context, id)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(manifest?.title ?: id, fontWeight = FontWeight.Medium)
                        Text("v${manifest?.version ?: "?"} · ${if (id in required) "必需" else if (enabled) "已启用" else "已停用"}" +
                            if (RuntimePluginLoader.isLoaded(id)) " · 已加载" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (id !in required) {
                        AppButton(onClick = { perform { AhupInstaller.setEnabled(context, id, !enabled) } },
                            variant = AppButtonVariant.Secondary) { Text(if (enabled) "停用" else "启用") }
                        AppButton(onClick = { uninstallId = id }, variant = AppButtonVariant.Secondary) { Text("卸载") }
                    }
                }
            }
            if (runtimePlugins.isEmpty()) Text("尚未安装插件")
            AppButton(onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
                variant = AppButtonVariant.Primary, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "正在处理…" else "从文件安装 .ahup")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            RuntimePluginLoader.lastErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    candidate?.let { c ->
        AppDialog(title = "安装官方插件", subtitle = "${c.manifest.title} v${c.manifest.version}",
            onDismiss = { candidate?.close(); candidate = null }, actions = listOf(
                AppDialogAction("取消", { candidate?.close(); candidate = null }, AppDialogActionStyle.Neutral),
                AppDialogAction("安装并启用", {
                    candidate = null
                    perform { AhupInstaller.install(context, c); AhupInstaller.setEnabled(context, c.manifest.id, true) }
                }, AppDialogActionStyle.Primary)
            )) {
            Text("官方签名及全部文件完整性验证通过。\n${c.manifest.summary}\n依赖：" +
                c.manifest.dependencies.joinToString { it.id }.ifEmpty { "无" })
        }
    }
    uninstallId?.let { id ->
        AppDialog(title = "卸载插件？", onDismiss = { uninstallId = null }, actions = listOf(
            AppDialogAction("取消", { uninstallId = null }, AppDialogActionStyle.Neutral),
            AppDialogAction("卸载并保留数据", { uninstallId = null; perform { AhupInstaller.uninstall(context, id) } },
                AppDialogActionStyle.Danger)
        )) { Text("入口和后台活动将停止。重新安装后可恢复原有数据。") }
    }
}
