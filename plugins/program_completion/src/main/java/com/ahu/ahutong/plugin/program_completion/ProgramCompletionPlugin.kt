package com.ahu.ahutong.plugin.program_completion

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.program_completion.ui.screen.main.ProgramCompletion
import com.ahu.ahutong.plugin.program_completion.ui.state.ProgramCompletionViewModel

class ProgramCompletionPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("program_completion", "培养方案", "培养方案完成情况与课程学分", PluginIcon.Resource(0), 0xFF3A8374, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    override fun onCreate(environment: PluginEnvironment) { PluginScope.environment = environment }

    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (!SessionStore.isLoggedIn()) {
            Button(onClick = { navigation?.open("account") }) { Text("请先登录校园账号") }
            return
        }
        val factory = remember(generation) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProgramCompletionViewModel(ProgramCompletionGateway()) as T
        } }
        val model: ProgramCompletionViewModel = viewModel(key = "program_completion.$generation", factory = factory)
        ProgramCompletion(onBack = { (host as? PluginHostV2)?.close() }, viewModel = model)
    }
}
