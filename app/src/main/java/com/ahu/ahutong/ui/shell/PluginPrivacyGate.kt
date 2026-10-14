package com.ahu.ahutong.ui.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.ui.plugin.PluginPrivacyConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PluginStorageErrorGate(onRetry: () -> Unit) {
    val activity = LocalActivity.current
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("本地加密存储暂时无法读取", style = MaterialTheme.typography.headlineSmall)
                Text("原始数据已保留，账户恢复和插件初始化已停止。请确认设备已解锁后重试。")
                Button(onClick = onRetry) { Text("重试读取") }
                OutlinedButton(onClick = { activity?.finish() }) { Text("退出") }
            }
        }
    }
}

/** This gate appears before bootstrap hooks, optional network requests, or account restoration. */
@Composable
internal fun PluginPrivacyGate(onAccepted: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var policy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        runCatching { withContext(Dispatchers.IO) {
            val resource = context.resources.getIdentifier("privacy_policy", "raw", context.packageName)
            require(resource != 0) { "隐私政策文件缺失，请安装完整 APK" }
            context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
        } }.onSuccess { policy = it; error = null }.onFailure { error = it.message ?: "无法读取隐私政策" }
    }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("安大通隐私政策", style = MaterialTheme.typography.headlineSmall)
                Text("请阅读完整政策后决定是否继续。功能插件可在初始化时选择。", style = MaterialTheme.typography.bodySmall)
                Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val document = policy
                    if (document == null && error == null) CircularProgressIndicator()
                    else if (document != null) Text(document, style = MaterialTheme.typography.bodyMedium)
                    else Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                error?.let { if (policy != null) Text(it, color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { activity?.finish() }, modifier = Modifier.weight(1f)) { Text("拒绝") }
                    if (policy == null && error != null) Button(onClick = { reload++ }, modifier = Modifier.weight(1f)) { Text("重试") }
                    else Button(onClick = {
                        runCatching { PluginPrivacyConsent.accept() }.onSuccess { onAccepted() }.onFailure { error = it.message }
                    }, enabled = !policy.isNullOrBlank(), modifier = Modifier.weight(1f)) { Text("同意并继续") }
                }
            }
        }
    }
}
