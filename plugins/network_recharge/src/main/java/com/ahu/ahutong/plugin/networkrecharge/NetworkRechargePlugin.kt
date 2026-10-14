package com.ahu.ahutong.plugin.networkrecharge

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
import kotlinx.coroutines.flow.flowOf

class NetworkRechargePlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("network_recharge", "网费充值", "校园网账户、套餐与缴费",
        PluginIcon.Resource(0), 0xFF5B85B1, "1.0.0", "AHUTong")
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
            AppStateCard.Empty(message = "登录校园账号后使用网费充值", actionLabel = "登录",
                onAction = { navigation?.open("plugin/account") })
            return
        }
        val factory = remember(host, generation) {
            val settings = services.resolve(PaymentKeyboardSetting::class.java) ?: object : PaymentKeyboardSetting {
                override val useBuiltInSecurePasswordKeyboard = flowOf(true)
            }
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = NetworkRechargeViewModel(
                    RepositoryNetworkRechargeSource(YcardGateway(account)),
                    services.resolve(BehaviorRecorder::class.java), settings
                ) as T
            }
        }
        val model: NetworkRechargeViewModel = viewModel(key = "plugin.network_recharge.$generation", factory = factory)
        NetworkRecharge(onBack = { (host as? PluginHostV2)?.close() }, viewModel = model)
    }
}
