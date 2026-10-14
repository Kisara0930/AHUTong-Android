package com.ahu.ahutong.plugin.campuscard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.*
import com.ahu.ahutong.personalization.action.ActionSource
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.ui.components.AppPageScaffold
import com.ahu.ahutong.ui.components.AppStateCard
import com.ahu.ahutong.ui.components.AppToggle
import kotlinx.coroutines.*
import java.util.UUID

class CampusCardPlugin : AhuPlugin, PluginLifecycle, PluginContributions, PaymentQrService {
    override val meta = PluginMeta("campus_card", "校园卡与账单", "余额、付款码、充值、账单与统计",
        PluginIcon.Resource(0), 0xFF537DA5, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var services: PluginServiceRegistry
    private val commands = PaymentQrOpenCommandStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val models = mutableSetOf<CampusCardViewModel>()
    private var qr: PaymentQrRepository? = null
    private var activeGeneration = -1L
    private var enabled = false
    private var requestedPage = "card"
    override val visible get() = commands.visible

    override fun onCreate(environment: PluginEnvironment) { services = environment.services }
    override fun onEnable() {
        enabled = true
        commands.activate(AccountGeneration.current, AccountGeneration.current)
        services.publish(PaymentQrService::class.java, this)
    }
    override fun onDisable() {
        enabled = false; commands.clear(); commands.setVisible(false)
        models.forEach { it.clearQrCode() }
        scope.launch { qr?.clearSensitive() }
    }
    override fun onDestroy() { onDisable(); scope.cancel() }
    override fun onAccountChanged() {
        commands.activate(AccountGeneration.current, AccountGeneration.current)
        commands.setVisible(false)
        models.forEach { it.clearQrCode() }; models.clear()
        BillingStatsViewModel.clearCache()
        val previous = qr; qr = null; activeGeneration = -1
        scope.launch { previous?.clearSensitive() }
    }
    override fun requestOpen() {
        if (!enabled || !SessionStore.isLoggedIn()) return
        commands.activate(AccountGeneration.current, AccountGeneration.current)
        commands.publish(UUID.randomUUID().toString(), "plugin-service", ActionSource.SUGGESTION)
    }
    override suspend fun prefetch(): Result<Unit> {
        if (!enabled || !SessionStore.isLoggedIn()) return Result.failure(IllegalStateException("校园卡未启用或未登录"))
        return repository().prefetchPredictively()
    }
    override suspend fun clearSensitive() {
        commands.clear(); commands.setVisible(false)
        models.forEach { it.clearQrCode() }
        qr?.clearSensitive()
    }

    private fun repository(): PaymentQrRepository {
        if (qr == null || activeGeneration != AccountGeneration.current) {
            val account = checkNotNull(services.resolve(CampusSessionService::class.java))
            val gateway = CampusCardGateway(account)
            qr = PaymentQrRepository(gateway::getQrcode)
            activeGeneration = AccountGeneration.current
        }
        return requireNotNull(qr)
    }

    @Composable
    private fun cardModel(generation: Long): CampusCardViewModel {
        val factory = remember(generation) {
            val account = checkNotNull(services.resolve(CampusSessionService::class.java))
            val gateway = CampusCardGateway(account)
            val cache = CampusCardCache(requireNotNull(SessionStore.currentUser()).xh)
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CampusCardViewModel(gateway, cache, repository()).also { models += it } as T
            }
        }
        val model: CampusCardViewModel = viewModel(key = "plugin.campus_card.$generation", factory = factory)
        DisposableEffect(model) { onDispose { model.clearQrCode(); models.remove(model) } }
        return model
    }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val account = checkNotNull(services.resolve(CampusSessionService::class.java))
        val state by account.state.collectAsState()
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (state != AhuSessionState.Status.Authenticated) {
            AppStateCard.Empty(message = "登录校园账号后使用校园卡", actionLabel = "登录",
                onAction = { navigation?.open("plugin/account") })
            return
        }
        val model = cardModel(generation)
        val factory = remember(host, generation) {
            val gateway = CampusCardGateway(account)
            val cache = CampusCardCache(requireNotNull(SessionStore.currentUser()).xh)
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                    CardBalanceDepositViewModel::class.java -> CardBalanceDepositViewModel(
                        RepositoryCardRechargeSource(gateway, cache), SessionStore, services.resolve(BehaviorRecorder::class.java))
                    BillingViewModel::class.java -> BillingViewModel(gateway)
                    BillingStatsViewModel::class.java -> BillingStatsViewModel(gateway)
                    else -> error("未知校园卡页面")
                } as T
            }
        }
        var page by rememberSaveable { mutableStateOf(requestedPage) }
        LaunchedEffect(navigation?.route, navigation?.requestId) {
            page = when (navigation?.route?.substringBefore('?')) {
                "billing" -> "billing"
                "billing_stats", "stats" -> "stats"
                "card_balance_deposit", "cmb_card_recharge" -> "card_balance_deposit"
                "payment_qr" -> { requestOpen(); "card" }
                else -> requestedPage
            }
            requestedPage = "card"
        }
        val back = { page = "card" }
        BackHandler(enabled = page != "card", onBack = back)
        when (page) {
            "billing" -> Billing(back, onOpenStats = { page = "stats" },
                viewModel = viewModel(key = "card.billing.$generation", factory = factory))
            "stats" -> BillingStats(onBack = { page = "billing" },
                viewModel = viewModel(key = "card.stats.$generation", factory = factory))
            "card_balance_deposit" -> CardBalanceDeposit(back,
                viewModel = viewModel(key = "card.recharge.$generation", factory = factory))
            else -> AppPageScaffold(title = "校园卡", onBack = { (host as? PluginHostV2)?.close() },
                content = {
                    CampusCard(model.balance, model.transitionBalance, model::refreshCardBalance, { page = it },
                        true, model, commands, services.resolve(BehaviorRecorder::class.java))
                    val defaultQr by model.showDefaultQr.collectAsState()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("首页默认显示付款码", modifier = Modifier.weight(1f))
                        AppToggle(defaultQr, model::setShowDefaultQr)
                    }
                })
        }
    }

    @Composable
    override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        val account = services.resolve(CampusSessionService::class.java) ?: return
        val state by account.state.collectAsState()
        val generation by AccountGeneration.changes.collectAsState()
        if (state != AhuSessionState.Status.Authenticated) return
        val model = cardModel(generation)
        CampusCard(model.balance, model.transitionBalance, model::refreshCardBalance, { route -> requestedPage = route; onOpen() },
            true, model, commands, services.resolve(BehaviorRecorder::class.java))
    }
}
