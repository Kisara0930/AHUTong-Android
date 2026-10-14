package com.ahu.ahutong.plugin.bathroom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.core.storage.PaymentKeyboardSetting
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.AhuSessionState
import com.ahu.ahutong.data.session.CampusSessionService
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.ui.components.AppStateCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf

class BathroomPlugin : AhuPlugin, PluginLifecycle, PluginContributions {
    override val meta = PluginMeta("bathroom", "浴室缴费", "浴室账户、余额与充值",
        PluginIcon.Resource(0), 0xFF68A5B5, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var services: PluginServiceRegistry
    override fun onCreate(environment: PluginEnvironment) { services = environment.services }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val account = checkNotNull(services.resolve(CampusSessionService::class.java)) { "请先启用账户组件" }
        val state by account.state.collectAsState()
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (state != AhuSessionState.Status.Authenticated) {
            AppStateCard.Empty(message = "登录校园账号后使用浴室缴费", actionLabel = "登录",
                onAction = { navigation?.open("plugin/account") })
            return
        }
        val factory = remember(host, generation) {
            val settings = services.resolve(PaymentKeyboardSetting::class.java) ?: object : PaymentKeyboardSetting {
                override val useBuiltInSecurePasswordKeyboard = flowOf(true)
            }
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = BathroomDepositViewModel(
                    RepositoryBathroomDepositSource(BathroomGateway(account), host.storage()),
                    services.resolve(BehaviorRecorder::class.java), settings, Dispatchers.IO
                ) as T
            }
        }
        val model: BathroomDepositViewModel = viewModel(key = "plugin.bathroom.$generation", factory = factory)
        BathroomDeposit(onBack = { (host as? PluginHostV2)?.close() }, viewmodel = model)
    }

    @Composable
    override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) = BathroomOpening(onOpen)
}
