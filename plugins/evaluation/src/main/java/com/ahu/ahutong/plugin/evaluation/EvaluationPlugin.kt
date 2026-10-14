package com.ahu.ahutong.plugin.evaluation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.evaluation.data.EvaluationRepository
import com.ahu.ahutong.plugin.evaluation.ui.screen.main.Evaluation
import com.ahu.ahutong.plugin.evaluation.ui.state.EvaluationViewModel

class EvaluationPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("evaluation", "教学评价", "教评问卷、预设与逐项或批量提交", PluginIcon.Resource(0), 0xFF8D629C, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    override fun onCreate(environment: PluginEnvironment) { PluginScope.environment = environment }
    override fun onAccountChanged() { EvaluationRepository.resetForAccountChange() }
    override fun onDisable() { EvaluationRepository.resetForAccountChange() }
    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (!SessionStore.isLoggedIn()) {
            Button(onClick = { navigation?.open("account") }) { Text("请先登录校园账号") }
            return
        }
        val factory = remember(generation) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = EvaluationViewModel() as T
        } }
        val model: EvaluationViewModel = viewModel(key = "evaluation.$generation", factory = factory)
        Evaluation(viewModel = model, onBack = { (host as? PluginHostV2)?.close() })
    }
}
