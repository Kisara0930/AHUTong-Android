package com.ahu.ahutong.plugin.personalization

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.personalization.runtime.SanitizedDiagnosticsSnapshot
import kotlinx.coroutines.launch

@Composable
internal fun PersonalizationPage(graph: PersonalizationGraph?, failure: String?, onRetry: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingConsent by remember { mutableStateOf<String?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("智能助手", style = MaterialTheme.typography.headlineMedium)
        Text("在本机学习使用习惯，提供功能建议。功能关闭后停止记录和预取；样本贡献与质量上报分别征得你的同意。")
        if (graph == null) {
            Text(failure ?: "正在载入本地模型和学习记录…")
            if (failure != null) Button(onClick = onRetry) { Text("重试") }
            return@Column
        }
        val preferences = graph.preferences
        val runtime = graph.runtime
        val enabled by preferences.personalizationEnabled.collectAsState(false)
        val prefetch by preferences.predictivePrefetchEnabled.collectAsState(false)
        val wifi by preferences.wifiOnlyPrefetch.collectAsState(true)
        val retention by preferences.behaviorRetentionDays.collectAsState(30)
        val telemetry by runtime.telemetryConsentEnabled.collectAsState()
        val contribution by runtime.bootstrapContributionStatus.collectAsState()
        val diagnostics by runtime.diagnostics.collectAsState()
        var historical by remember { mutableStateOf(false) }
        var snapshot by remember { mutableStateOf<SanitizedDiagnosticsSnapshot?>(null) }
        fun update(action: suspend () -> Unit) {
            scope.launch {
                try { action(); notice = null }
                catch (error: Exception) { notice = error.message ?: "操作失败，请重试" }
            }
        }
        SettingSwitch("本地个性化建议", enabled) { update { preferences.setPersonalizationEnabled(it) } }
        SettingSwitch("预测式预取", prefetch) { update {
            preferences.setPredictivePrefetchEnabled(it)
            if (!it) runtime.cancelPredictivePrefetch()
        } }
        SettingSwitch("仅 Wi-Fi 预取", wifi) { update { preferences.setWifiOnlyPrefetch(it) } }
        Text("记录保留 $retention 天")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days ->
                FilterChip(selected = retention == days, onClick = { update { preferences.setBehaviorRetentionDays(days) } }, label = { Text("$days 天") })
            }
        }
        HorizontalDivider()
        Text("可选贡献", style = MaterialTheme.typography.titleLarge)
        Text("当前账号：${if (diagnostics.profileActive) "学习档案已就绪" else "请先登录并开启本地个性化"}")
        SettingSwitch("模型质量汇总上报", telemetry, diagnostics.profileActive) { value ->
            if (value) pendingConsent = "quality"
            else update { preferences.setModelQualityTelemetryOnboardingChoice(false); runtime.setTelemetryConsent(false) }
        }
        SettingSwitch("贡献训练样本", contribution.enabled, diagnostics.profileActive) { value ->
            if (value) pendingConsent = "bootstrap"
            else update { runtime.setBootstrapTrainingConsent(false) }
        }
        if (!contribution.enabled) SettingSwitch("首次贡献包含已有样本", historical) { historical = it }
        Text("待贡献 ${contribution.pendingExamples} 条 · 已贡献 ${contribution.contributedExamples} 条")
        if (pendingConsent != null) AlertDialog(
            onDismissRequest = { pendingConsent = null }, title = { Text("确认可选贡献") },
            text = { Text(if (pendingConsent == "quality")
                "将向安大通服务器发送模型质量的汇总统计，用于评估本地模型。此项与本地学习独立，可随时关闭并请求删除已贡献的数据。"
                else "将向安大通服务器贡献经过现有隐私过滤的训练样本，包括行为特征和训练标签，用于改进模型。${if (historical) "本次包含已有样本。" else "只贡献之后产生的样本。"}关闭后会停止贡献并请求删除。") },
            confirmButton = { TextButton(onClick = {
                val choice = pendingConsent; pendingConsent = null
                update { if (choice == "quality") {
                    preferences.setModelQualityTelemetryOnboardingChoice(true); runtime.setTelemetryConsent(true)
                } else runtime.setBootstrapTrainingConsent(true, historical) }
            }) { Text("同意并开启") } },
            dismissButton = { TextButton(onClick = { pendingConsent = null }) { Text("取消") } }
        )
        HorizontalDivider()
        Text("本地诊断", style = MaterialTheme.typography.titleLarge)
        Text("模型阶段 ${diagnostics.stage} · 模型类型 ${diagnostics.tier} · 混合权重 ${diagnostics.lambda}")
        Text("预取状态 ${diagnostics.preparationState} · 最近结果 ${diagnostics.lastResolution ?: "暂无"}")
        diagnostics.lastFailure?.let { Text("最近错误：$it") }
        Button(onClick = { update { snapshot = runtime.sanitizedDiagnosticsSnapshot() } }) { Text("刷新详细诊断") }
        snapshot?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { clearDialog = true }) { Text("清除当前账号的学习记录") }
        if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清除学习记录？") },
            text = { Text("清除当前账号的本地学习记录和模型。此操作无法撤销。") },
            confirmButton = { TextButton(onClick = { clearDialog = false; update { runtime.clearLearningRecord(); snapshot = null } }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } })
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}
