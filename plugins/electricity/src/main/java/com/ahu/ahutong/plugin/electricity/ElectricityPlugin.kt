package com.ahu.ahutong.plugin.electricity

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.core.storage.PaymentKeyboardSetting
import com.ahu.ahutong.data.session.*
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.preset.PresetSuggestions
import com.ahu.ahutong.ui.components.AppStateCard
import com.ahu.ahutong.ui.components.AppDialog
import com.ahu.ahutong.ui.components.AppDialogAction
import com.ahu.ahutong.ui.components.AppDialogActionStyle
import com.ahu.ahutong.ui.components.AppSelectField
import com.ahu.ahutong.ui.components.AppSelectOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class ElectricityPlugin : AhuPlugin, PluginLifecycle, PluginContributions, PluginWorkHandler {
    override val meta = PluginMeta("electricity", "电费", "电费充值、房间管理、用电预测与预警",
        PluginIcon.Resource(0), 0xFFE4A24A, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var services: PluginServiceRegistry
    private lateinit var alerts: LocalElectricityAlertStore
    private var scope: CoroutineScope? = null
    private val alertModels = mutableSetOf<ElectricityAlertViewModel>()
    private var requestedRoom by mutableStateOf<com.ahu.ahutong.data.model.RoomSelectionInfo?>(null)

    override fun onCreate(environment: PluginEnvironment) {
        services = environment.services
        alerts = LocalElectricityAlertStore(environment.context.applicationContext)
    }
    override fun onEnable() {
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { work ->
            work.launch { alerts.activateProfile(SessionStore.currentUser()?.xh) }
            work.launch {
                alerts.configuration.collectLatest { config ->
                    val scheduler = services.resolve(PluginWorkScheduler::class.java) ?: return@collectLatest
                    if (config.enabled && config.rooms.isNotEmpty()) {
                        scheduler.enqueue("electricity", "check_alerts", "daily_alerts", repeatIntervalMs = 86_400_000,
                            network = PluginWorkNetwork.CONNECTED)
                    } else scheduler.cancel("electricity", "daily_alerts")
                }
            }
        }
    }
    override fun onDisable() {
        scope?.cancel(); scope = null
        alertModels.forEach { it.deactivate() }
        services.resolve(PluginWorkScheduler::class.java)?.cancelAll("electricity")
        services.resolve(PluginNotificationScheduler::class.java)?.cancel("electricity")
    }
    override fun onDestroy() { scope?.cancel(); scope = null; alertModels.forEach { it.deactivate() }; alertModels.clear() }
    override fun onAccountChanged() {
        alertModels.forEach { it.deactivate() }; requestedRoom = null
        scope?.launch { alerts.activateProfile(SessionStore.currentUser()?.xh) }
    }

    override suspend fun run(taskId: String): PluginWorkResult {
        if (taskId != "check_alerts") return PluginWorkResult.FAILURE
        val profile = SessionStore.currentUser()?.xh ?: return PluginWorkResult.SUCCESS
        val account = services.resolve(CampusSessionService::class.java) ?: return PluginWorkResult.RETRY
        if (account.state.value != AhuSessionState.Status.Authenticated) return PluginWorkResult.SUCCESS
        alerts.activateProfile(profile)
        val checker = ElectricityAlertChecker(alerts.files, RepositoryElectricityUsageSource(YcardGateway(account))) {
            SessionStore.currentUser()?.xh == it && account.state.value == AhuSessionState.Status.Authenticated
        }
        return try {
            val today = LocalDate.now()
            val rooms = checker.check(profile, alerts.configuration.value, today)
            if (rooms.isNotEmpty() && checker.claimPrompt(profile, today)) {
                services.resolve(PluginNotificationScheduler::class.java)?.notify("electricity", "电费余额提醒",
                    rooms.joinToString("；") { "${it.label}预计不足${it.thresholdDays}天" }, "plugin/electricity")
            }
            PluginWorkResult.SUCCESS
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { PluginWorkResult.RETRY }
    }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val account = checkNotNull(services.resolve(CampusSessionService::class.java))
        val state by account.state.collectAsState()
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (state != AhuSessionState.Status.Authenticated) {
            AppStateCard.Empty(message = "登录校园账号后使用电费服务", actionLabel = "登录",
                onAction = { navigation?.open("plugin/account") })
            return
        }
        val factory = remember(host, generation) {
            val settings = services.resolve(PaymentKeyboardSetting::class.java) ?: object : PaymentKeyboardSetting {
                override val useBuiltInSecurePasswordKeyboard = flowOf(true)
            }
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ElectricityDepositViewModel(
                    RepositoryElectricityDepositSource(YcardGateway(account), ElectricityCache(requireNotNull(SessionStore.currentUser()).xh)),
                    services.resolve(BehaviorRecorder::class.java), services.resolve(PresetSuggestions::class.java), settings, alerts
                ) as T
            }
        }
        LaunchedEffect(generation) { alerts.activateProfile(SessionStore.currentUser()?.xh) }
        val model: ElectricityDepositViewModel = viewModel(key = "plugin.electricity.$generation", factory = factory)
        var page by rememberSaveable { mutableStateOf("pay") }
        LaunchedEffect(navigation?.route, navigation?.requestId) {
            page = when (navigation?.route?.substringBefore('?')) {
                "electricity_recent_rooms" -> "rooms"
                "electricity_alert_settings" -> "alerts"
                else -> "pay"
            }
        }
        val back = { page = "pay" }
        BackHandler(enabled = page != "pay", onBack = back)
        when (page) {
            "rooms" -> ElectricityRecentRooms(back, back, model)
            "alerts" -> AlertSettingsPage(back, factory)
            else -> ElectricityDeposit(onBack = { (host as? PluginHostV2)?.close() },
                onOpenRecentRooms = { page = "rooms" }, onOpenAlertSettings = { page = "alerts" }, viewModel = model,
                initialSelection = requestedRoom, onInitialSelectionConsumed = { requestedRoom = null })
        }
    }

    @Composable
    override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) = ElectricityCard(onOpen)

    @Composable
    private fun AlertSettingsPage(onBack: () -> Unit, factory: ViewModelProvider.Factory) {
        // Alert configuration must not change the payment screen's selected room.
        val owner = remember {
            object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        val model: ElectricityDepositViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
        ElectricityAlertSettings(onBack, model)
    }

    @Composable
    override fun Foreground(host: PluginHostServices) {
        val account = services.resolve(CampusSessionService::class.java) ?: return
        val state by account.state.collectAsState()
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        val factory = remember(generation) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ElectricityAlertViewModel(
                    alerts, RepositoryElectricityUsageSource(YcardGateway(account)), SessionStore
                ).also { alertModels += it } as T
            }
        }
        val model: ElectricityAlertViewModel = viewModel(key = "plugin.electricity.notice.$generation", factory = factory)
        DisposableEffect(model) { onDispose { model.deactivate(); alertModels.remove(model) } }
        val owner = LocalLifecycleOwner.current
        var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        DisposableEffect(owner, state, model) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) { resumed = true; model.resumeSession(state == AhuSessionState.Status.Authenticated) }
                if (event == Lifecycle.Event.ON_PAUSE) resumed = false
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(state, generation) { model.resumeSession(state == AhuSessionState.Status.Authenticated) }
        val noQr = remember { MutableStateFlow(false) }
        val qrVisible by (services.resolve(PaymentQrService::class.java)?.visible ?: noQr).collectAsState()
        val pending by model.pending.collectAsState()
        val rooms by model.presented.collectAsState()
        val allowed = resumed && state == AhuSessionState.Status.Authenticated && !qrVisible
        LaunchedEffect(pending, allowed) { if (allowed && pending.isNotEmpty()) model.showPending() }
        if (allowed && rooms.isNotEmpty()) {
            var selected by remember(rooms) { mutableStateOf(rooms.first()) }
            AppDialog(title = "电费余额提醒", onDismiss = model::dismiss, actions = listOf(
                AppDialogAction("稍后", model::dismiss),
                AppDialogAction("是，去充值", {
                    requestedRoom = selected.selection; model.dismiss(); navigation?.open("plugin/electricity")
                }, AppDialogActionStyle.Primary)
            ), content = {
                if (rooms.size > 1) AppSelectField(label = "需要充值的房间", selected = selected,
                    options = rooms.map { AppSelectOption(it, it.label) }, onSelected = { selected = it })
                Text("${selected.label}电费余额可能不足以继续使用${selected.thresholdDays}天，是否充值？")
            })
        }
    }
}
