package com.ahu.ahutong.ui.shell

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginContributions
import com.ahu.ahutong.core.plugin.PluginNavigation
import com.ahu.ahutong.core.plugin.LocalPluginNavigation
import com.ahu.ahutong.core.plugin.PluginHomeCardManager
import com.ahu.ahutong.core.plugin.PaymentQrService
import com.ahu.ahutong.ui.plugin.AhupCandidate
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.AhupInstaller
import com.ahu.ahutong.ui.plugin.AhupManifest
import com.ahu.ahutong.ui.plugin.PluginHostServicesImpl
import com.ahu.ahutong.ui.plugin.PluginRegistry
import com.ahu.ahutong.ui.plugin.PluginPrivacyConsent
import com.ahu.ahutong.ui.plugin.RuntimePluginLoader
import com.ahu.ahutong.ui.plugin.PluginServices
import com.ahu.ahutong.ui.plugin.HostCrashRecovery
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.data.session.AhuSessionState
import androidx.compose.runtime.saveable.rememberSaveable
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.runtime.PersonalizationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimal host UI. No feature ViewModel, session implementation, or business repository is linked here. */
@Composable
fun PluginShell(requestedPluginId: String? = null, requestedRoute: String = "", onRequestConsumed: () -> Unit = {},
    requestedRequestId: Long = 0,
    hostSettings: @Composable (String, (String) -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
    resolveSuggestionDestination: (AppActionId) -> Pair<String, String>? = { null },
    onPluginRouteChanged: (String?, String?) -> Unit = { _, _ -> },
    onSensitiveUiChanged: (Boolean) -> Unit = {}) {
    var consentResult by remember { mutableStateOf(runCatching { PluginPrivacyConsent.isAccepted() }) }
    if (consentResult.isFailure) {
        PluginStorageErrorGate { consentResult = runCatching { PluginPrivacyConsent.isAccepted() } }
        return
    }
    if (consentResult.getOrNull() != true) {
        PluginPrivacyGate { consentResult = Result.success(true) }
        return
    }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val preferences = remember { appContext.getSharedPreferences("plugin_shell", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var initialized by remember { mutableStateOf(AhupGate.isInitialized(appContext)) }
    var bootstrapReady by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(if (initialized) "home" else "setup") }
    var primaryRoute by rememberSaveable { mutableStateOf("home") }
    var showManagement by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf(preferences.getString("market_url", "").orEmpty()) }
    var endpointDraft by remember { mutableStateOf(endpoint) }
    var homeOrder by remember { mutableStateOf(preferences.getString("home_order", "").orEmpty().split(',').filter { it.isNotBlank() }) }
    var selected by remember { mutableStateOf(preferences.getStringSet("selection", emptySet()).orEmpty().toSet()) }
    var catalogue by remember { mutableStateOf<List<MarketPlugin>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf<Float?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var activeClient by remember { mutableStateOf<PluginMarketClient?>(null) }
    var openPluginId by remember { mutableStateOf<String?>(null) }
    var openRoute by remember { mutableStateOf("") }
    var backStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var navigationSequence by remember { mutableLongStateOf(0L) }
    var confirmation by remember { mutableStateOf<ShellConfirmation?>(null) }
    val registry by PluginRegistry.plugins.collectAsState()
    val installed = remember(generation) {
        runCatching { AhupInstaller.installedIds(appContext).mapNotNull { AhupInstaller.manifestOf(appContext, it) } }
            .getOrDefault(emptyList())
    }
    val required = remember(generation) { runCatching { AhupGate.requiredIds(appContext) }.getOrDefault(setOf("account")) }
    val busy = progress != null
    val controller = PluginServices.resolve(PersonalizationController::class.java)
    val payment = PluginServices.resolve(PaymentQrService::class.java)
    val qrVisible = payment?.visible?.collectAsState()?.value == true
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val sensitive = !initialized || busy || confirmation != null || qrVisible || imeVisible ||
        openPluginId in setOf("account", "campus_card", "electricity", "bathroom", "network_recharge", "personalization", "evaluation", "student_mail", "lost_found")
    val lifecycle = LocalLifecycleOwner.current
    val observedRoute = when {
        openPluginId == "grade_exam" && openRoute == "grade_exam" -> "grade"
        openPluginId == "account" && openRoute == "account" -> "login"
        openPluginId == "campus_guide" && openRoute == "campus_guide" -> "school_calendar"
        openPluginId != null -> openRoute
        !initialized -> "setup"
        !showManagement -> if (showTools) "widgets" else primaryRoute
        tab == "home" -> "home"
        tab == "settings" -> "settings"
        else -> "tools"
    }
    DisposableEffect(controller, lifecycle) {
        fun update() {
            val foreground = lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val interactive = foreground && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                context.getSystemService(android.os.PowerManager::class.java).isInteractive
            controller?.setForeground(foreground, interactive)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller?.setForeground(false, false) }
    }
    LaunchedEffect(controller, observedRoute, openPluginId) {
        controller?.onRouteChanged(observedRoute)
        onPluginRouteChanged(openPluginId, observedRoute)
    }
    LaunchedEffect(controller, sensitive) {
        controller?.setSensitiveUiVisible(sensitive)
        onSensitiveUiChanged(sensitive)
    }

    fun moveHomeCard(id: String, direction: Int) {
        val ids = registry.filter { plugin -> installed.firstOrNull { it.id == plugin.meta.id }?.homeCard == true }
            .map { it.meta.id }.sortedWith(compareBy<String> { homeOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it })
            .toMutableList()
        val index = ids.indexOf(id)
        val destination = index + direction
        if (index < 0 || destination !in ids.indices) return
        java.util.Collections.swap(ids, index, destination)
        homeOrder = ids
        preferences.edit().putString("home_order", ids.joinToString(",")).apply()
    }

    fun navigate(route: String, explicitId: String? = null, keepBack: Boolean = true) {
        navigationSequence++
        val normalized = route.removePrefix("plugin/")
        val segment = normalized.substringBefore('/').substringBefore('?')
        if (explicitId == null && segment in setOf("home", "settings", "widgets")) {
            openPluginId = null; backStack = emptyList(); showManagement = false; tab = "home"
            showTools = segment == "widgets"
            if (!showTools) primaryRoute = segment
            return
        }
        val destination = explicitId ?: when(segment) {
            "login", "login_web", "me", "accounts" -> "account"
            "grade", "exam" -> "grade_exam"
            "info" -> "schedule"
            "programCompletion" -> "program_completion"
            "freeClassroom" -> "free_classroom"
            "xuexiaotong", "learning" -> "chaoxing"
            "repository_index" -> "repository"
            "repository_downloads", "repository_settings", "repository_directory", "repository_item" -> "repository"
            "school_calendar", "phone_book", "identity_code", "calendar", "cainiao" -> "campus_guide"
            "payment_qr", "card_balance_deposit", "cmb_card_recharge", "card_recharge", "card_bill" -> "campus_card"
            "bathroom_deposit" -> "bathroom"
            "electricity_pay", "electricity_recent_rooms", "electricity_alert_settings" -> "electricity"
            "preferences", "theme_lab" -> "appearance"
            "circle" -> "campus_circle"
            else -> segment
        }
        if (!AhupInstaller.isEnabled(appContext, destination)) {
            error = if (AhupInstaller.manifestOf(appContext, destination) == null)
                "需要先安装 $destination 组件，请导入对应插件包或前往市场。"
                else "该功能已停用，请先在插件管理中启用。"
            openPluginId = null
            showManagement = true
            tab = if (initialized) "installed" else "setup"
            return
        }
        if (keepBack && openPluginId != null) backStack = backStack + (openPluginId!! to openRoute)
        else if (!keepBack) backStack = emptyList()
        openPluginId = destination
        openRoute = if (route.startsWith("plugin/")) normalized.substringAfter('/', segment) else normalized.ifBlank { destination }
        showManagement = false; showTools = false
        if (destination == "schedule" && openRoute == "schedule") primaryRoute = "schedule"
        if (destination == "chaoxing") primaryRoute = "xuexiaotong"
    }

    fun closePlugin() {
        navigationSequence++
        val previous = backStack.lastOrNull()
        if (previous != null && AhupInstaller.isEnabled(appContext, previous.first)) {
            backStack = backStack.dropLast(1)
            openPluginId = previous.first
            openRoute = previous.second
        } else { backStack = emptyList(); openPluginId = null }
    }

    fun acceptSuggestedAction(action: AppActionId) {
        when (action) {
            AppActionId.OPEN_HOME -> navigate("home")
            AppActionId.OPEN_TOOLS -> navigate("widgets")
            AppActionId.OPEN_SETTINGS, AppActionId.EDIT_HOME -> navigate("settings")
            // The controller has already requested QR opening through the host feature bridge.
            AppActionId.OPEN_PAYMENT_QR -> Unit
            else -> {
                val target = resolveSuggestionDestination(action)
                if (target != null) navigate(target.second, target.first)
                else error = "该建议功能暂时无法打开，请从已安装功能进入。"
            }
        }
    }

    fun refresh() {
        PluginRegistry.reload(appContext)
        initialized = AhupGate.isInitialized(appContext)
        generation++
    }

    fun updateSelection(ids: Set<String>) {
        selected = ids
        preferences.edit().putStringSet("selection", ids).apply()
    }

    fun execute(label: String, block: suspend () -> Unit) {
        if (busy) return
        progress = label
        progressFraction = null
        error = null
        notice = null
        operation = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                notice = "操作已取消，已完成的安装和选择会保留。"
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "操作失败，请重试"
            } finally {
                runCatching { refresh() }.onFailure { error = it.message ?: "读取插件状态失败" }
                progress = null
                progressFraction = null
                operation = null
                activeClient = null
            }
        }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        execute("正在验证本地插件…") {
            val count = withCandidateBatch { candidates ->
            withContext(Dispatchers.IO) {
                require(uris.size <= 128) { "一次最多导入 128 个插件包" }
                uris.forEachIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    progress = "验证插件 ${index + 1}/${uris.size}"
                    candidates += AhupInstaller.inspect(appContext, uri)
                }
            }
            progress = "正在安装已验证的插件…"
            withContext(Dispatchers.IO) { AhupInstaller.installBatch(appContext, candidates) }
            candidates.size
            }
            notice = "已安装 $count 个插件。请勾选需要使用的功能，再确认启用。"
            tab = if (initialized) "installed" else "setup"
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::importUris)
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) execute("正在读取插件目录…") {
            val uris = withContext(Dispatchers.IO) { pluginFilesInDirectory(appContext, tree) }
            require(uris.isNotEmpty()) { "所选目录中没有 .ahup 文件" }
            val count = withCandidateBatch { candidates ->
            withContext(Dispatchers.IO) {
                require(uris.size <= 128) { "目录中的插件超过 128 个，请分批导入" }
                uris.forEachIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    progress = "验证插件 ${index + 1}/${uris.size}"
                    candidates += AhupInstaller.inspect(appContext, uri)
                }
            }
            progress = "正在安装插件组合…"
            withContext(Dispatchers.IO) { AhupInstaller.installBatch(appContext, candidates) }
            candidates.size
            }
            notice = "已从目录安装 $count 个插件。安装不会自动启用可选功能。"
            tab = if (initialized) "installed" else "setup"
        }
    }

    suspend fun installSelection() {
        val missing = selected.filter { AhupInstaller.manifestOf(appContext, it) == null }.toSet()
        if (missing.isNotEmpty()) {
            require(endpoint.isNotBlank()) { "所选功能尚未安装，请导入本地插件包或配置市场地址" }
            val client = PluginMarketClient(endpoint)
            activeClient = client
            progress = "正在解析插件依赖…"
            val resolution = client.resolve(appContext, selected + required)
            withCandidateBatch { candidates ->
            resolution.downloads.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                progress = "下载 ${item.manifest.title} (${index + 1}/${resolution.downloads.size})"
                candidates += client.download(appContext, item) { completed, total ->
                    progressFraction = if (total > 0) completed.toFloat() / total else null
                }
            }
            progress = "正在安装插件组合…"
            progressFraction = null
            if (candidates.isNotEmpty()) withContext(Dispatchers.IO) { AhupInstaller.installBatch(appContext, candidates) }
            }
        }
        withContext(Dispatchers.IO) {
            if (!initialized) AhupGate.completeInitialization(appContext, selected)
            else selected.forEach { AhupInstaller.setEnabled(appContext, it, true) }
        }
        tab = "home"
        showManagement = false
        primaryRoute = "home"
        notice = "功能已就绪，可随时在插件管理中调整。"
    }

    LaunchedEffect(Unit) {
        progress = "正在检查基础插件…"
        try {
            withContext(Dispatchers.IO) { AhupGate.initializeBootstrap(appContext).getOrThrow() }
            bootstrapReady = true
            refresh()
        } catch (failure: Exception) {
            error = failure.message ?: "基础插件初始化失败"
            tab = "settings"
        } finally { progress = null }
    }

    LaunchedEffect(requestedPluginId, requestedRoute, requestedRequestId, bootstrapReady, initialized) {
        if (bootstrapReady && initialized && requestedPluginId != null) {
            navigate(requestedRoute.ifBlank { requestedPluginId }, requestedPluginId, keepBack = false)
            onRequestConsumed()
        }
    }

    if (initialized) {
        registry.filter { plugin -> installed.firstOrNull { it.id == plugin.meta.id }?.foreground == true }.forEach { plugin ->
            PluginForegroundContent(context, plugin, onNavigate = { navigate(it) })
        }
    }

    val openedId = openPluginId
    fun openManagement() { openPluginId = null; backStack = emptyList(); showManagement = true; showTools = false; tab = "installed" }
    val sessionStatus by AhuSessionState.status.collectAsState()
    LaunchedEffect(initialized, bootstrapReady, sessionStatus, openPluginId, primaryRoute) {
        if (initialized && bootstrapReady && !showManagement && openPluginId != "account" &&
            (!SessionStore.isLoggedIn() || sessionStatus == AhuSessionState.Status.Expired)) navigate("login", "account", false)
    }
    if (initialized && !showManagement && (openedId == null ||
            (openedId == "schedule" && openRoute == "schedule") || openedId == "chaoxing")) {
        if (showTools) {
            BackHandler { showTools = false }
            OriginalToolsPage({ showTools = false }, { navigate(it) }, ::openManagement)
        } else OriginalMainFrame(primaryRoute, { route ->
            if (route == "home" || route == "settings") navigate(route, keepBack = false)
            else navigate(route, keepBack = false)
        }) {
            when (primaryRoute) {
                "settings" -> hostSettings(endpoint, { navigate(it) }, ::openManagement)
                "schedule", "xuexiaotong" -> {
                    val id = if (primaryRoute == "schedule") "schedule" else "chaoxing"
                    val plugin = registry.firstOrNull { it.meta.id == id }
                    val navigation = object : PluginNavigation {
                        override val route = openRoute.ifBlank { primaryRoute }
                        override val requestId = navigationSequence
                        override fun open(route: String) = navigate(route)
                        override fun back() = navigate("home", keepBack = false)
                    }
                    PluginEntryPage(context, id, plugin, navigation, { navigate("home", keepBack = false) }, ::openManagement,
                        showHeader = false, suggestion = {})
                }
                else -> com.ahu.ahutong.ui.shell.home.OriginalHomeDashboard(
                    onNavigate = { navigate(it) },
                    schedule = { registry.firstOrNull { it.meta.id == "schedule" }?.let { PluginHomeCard(context, it) { navigate("schedule") } } },
                    campusCard = { registry.firstOrNull { it.meta.id == "campus_card" }?.let { PluginHomeCard(context, it) { navigate("campus_card") } } },
                    weather = { registry.firstOrNull { it.meta.id == "weather" }?.let { PluginHomeCard(context, it) { navigate("weather") } } }
                )
            }
        }
        return
    }
    if (openedId != null) {
        val plugin = registry.firstOrNull { it.meta.id == openedId }
        val navigation = object : PluginNavigation {
            override val route: String = openRoute
            override val requestId: Long = navigationSequence
            override fun open(route: String) = navigate(route)
            override fun back() = closePlugin()
        }
        PluginEntryPage(context, openedId, plugin, navigation, onClose = ::closePlugin,
            onRepair = { openPluginId = null; tab = "settings"; showManagement = true },
            suggestion = { PluginSuggestionCard(controller, sensitive, ::acceptSuggestedAction) })
        return
    }

    BackHandler(initialized && showManagement) { showManagement = false; tab = "home"; primaryRoute = "settings" }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (initialized) "安大通" else "配置你的安大通", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text(if (initialized) "只启用你需要的校园功能" else "基础组件已随 APK 提供，选择需要的功能后开始使用。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val tabs = if (initialized) listOf("market" to "插件市场", "installed" to "已安装", "settings" to "修复")
                        else listOf("setup" to "功能选择", "market" to "市场", "settings" to "设置/修复")
                    tabs.forEach { (key, label) ->
                        TextButton(onClick = { tab = key }, enabled = !busy) {
                            Text(label, fontWeight = if (tab == key) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                if (busy) {
                    if (progressFraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progressFraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (operation != null) TextButton(onClick = {
                            operation?.cancel()
                            activeClient?.cancel()
                        }) { Text("取消") }
                    }
                }
                error?.let { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                PluginSuggestionCard(controller, sensitive, ::acceptSuggestedAction)
                HorizontalDivider()
                when (tab) {
                    "home" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        items(registry.filter { plugin -> installed.firstOrNull { it.id == plugin.meta.id }?.userVisible != false }
                            .sortedWith(compareBy<AhuPlugin> { homeOrder.indexOf(it.meta.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                                .thenBy { it.meta.id }), key = { it.meta.id }) { plugin ->
                            val manifest = installed.firstOrNull { it.id == plugin.meta.id }
                            val cards = PluginServices.resolve(PluginHomeCardManager::class.java)
                            if (manifest?.homeCard == true && cards?.isVisible(plugin.meta.id) != false) {
                                PluginHomeCard(context, plugin, onOpen = { navigate(plugin.meta.id, keepBack = false) })
                            }
                            Card(onClick = { navigate(plugin.meta.id, keepBack = false) }, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(plugin.meta.title, style = MaterialTheme.typography.titleMedium)
                                    Text(plugin.meta.summary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { OutlinedButton(onClick = { tab = "market" }, modifier = Modifier.fillMaxWidth()) { Text("添加更多功能") } }
                    }
                    "setup", "installed" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入插件包") }
                            OutlinedButton(onClick = { directoryPicker.launch(null) }, enabled = !busy) { Text("导入目录") }
                        }
                        val choices = (installed + catalogue.map { it.manifest }).distinctBy { it.id }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            if (choices.isEmpty()) item { Text("正在准备基础插件。你也可以导入与 APK 放在同一目录的 .ahup 文件。") }
                            items(choices.filter { it.userVisible || it.id in required }, key = { it.id }) { manifest ->
                                val isInstalled = installed.any { it.id == manifest.id }
                                val enabled = isInstalled && AhupInstaller.isEnabled(appContext, manifest.id)
                                FeatureChoice(manifest, checked = manifest.id in selected || manifest.id in required || (initialized && enabled),
                                    required = manifest.id in required, enabled = !busy,
                                    status = if (!isInstalled) "待下载安装" else if (enabled) "已启用" else "已安装，未启用",
                                    onChecked = { checked ->
                                        if (initialized && enabled && !checked) {
                                            execute("正在停用 ${manifest.title}…") {
                                                withContext(Dispatchers.IO) { AhupInstaller.setEnabled(appContext, manifest.id, false) }
                                                updateSelection(selected - manifest.id)
                                            }
                                        } else updateSelection(if (checked) selected + manifest.id else selected - manifest.id)
                                    },
                                    onUninstall = if (initialized && isInstalled && manifest.id !in required) ({
                                        confirmation = ShellConfirmation("卸载 ${manifest.title}？", "会停止该功能并保留原有数据。", "卸载") {
                                            execute("正在卸载…") {
                                                withContext(Dispatchers.IO) { AhupInstaller.uninstall(appContext, manifest.id) }
                                                updateSelection(selected - manifest.id)
                                            }
                                        }
                                    }) else null)
                            }
                            if (installed.any { !it.userVisible && it.id !in required }) item {
                                Text("共享组件由所选功能自动管理，无需单独选择。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = { execute("正在应用功能选择…") { installSelection() } },
                            enabled = bootstrapReady && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (initialized) "启用所选功能" else "完成初始化，开始使用")
                        }
                    }
                    "market" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                execute("正在获取兼容插件…") {
                                    require(endpoint.isNotBlank()) { "请先在设置中填写插件市场地址，或通过本地文件安装" }
                                    val client = PluginMarketClient(endpoint)
                                    activeClient = client
                                    catalogue = client.catalog()
                                    notice = "找到 ${catalogue.count { it.manifest.userVisible }} 个兼容功能。"
                                }
                            }, enabled = !busy) { Text("刷新市场") }
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("本地安装") }
                        }
                        if (endpoint.isBlank()) Text("尚未配置市场地址。测试时可导入随 APK 提供的 .ahup 文件，或在设置中连接测试市场。",
                            style = MaterialTheme.typography.bodySmall)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(catalogue.filter { it.manifest.userVisible }, key = { it.manifest.id }) { item ->
                                val current = installed.firstOrNull { it.id == item.manifest.id }
                                FeatureChoice(item.manifest, item.manifest.id in selected || item.manifest.id in required,
                                    item.manifest.id in required, !busy,
                                    if (current == null) "${formatBytes(item.archiveSize)} · 可安装"
                                    else if (current.versionCode < item.manifest.versionCode) "可更新至 ${item.manifest.versionName}"
                                    else "已安装 ${current.versionName}",
                                    onChecked = { checked -> updateSelection(if (checked) selected + item.manifest.id else selected - item.manifest.id) })
                            }
                        }
                        Button(onClick = {
                            execute("正在解析插件版本…") {
                                require(endpoint.isNotBlank()) { "请先配置市场地址" }
                                val client = PluginMarketClient(endpoint)
                                activeClient = client
                                val resolution = client.resolve(appContext, selected + required)
                                withCandidateBatch { candidates ->
                                resolution.downloads.forEachIndexed { index, item ->
                                    progress = "下载 ${item.manifest.title} (${index + 1}/${resolution.downloads.size})"
                                    candidates += client.download(appContext, item) { read, total -> progressFraction = read.toFloat() / total }
                                }
                                progress = "正在安装已验证的插件…"
                                progressFraction = null
                                if (candidates.isNotEmpty()) withContext(Dispatchers.IO) { AhupInstaller.installBatch(appContext, candidates) }
                                withContext(Dispatchers.IO) { selected.forEach { AhupInstaller.setEnabled(appContext, it, true) } }
                                notice = if (candidates.isEmpty()) "所选功能已是兼容版本。" else "已安装 ${candidates.size} 个插件；已加载的更新需要重启应用。"
                                }
                                tab = if (initialized) "installed" else "setup"
                            }
                        }, enabled = !busy && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("安装或更新所选功能") }
                    }
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        item { TextButton(onClick = { showManagement = false; tab = "home"; primaryRoute = "settings" }) { Text("返回应用设置") } }
                        item {
                            Text("插件市场地址", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value = endpointDraft, onValueChange = { endpointDraft = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                                placeholder = { Text("https://… 或 http://127.0.0.1:8765") })
                            Button(onClick = {
                                runCatching { if (endpointDraft.isNotBlank()) PluginMarketClient.checkedUri(endpointDraft.trim()) }
                                    .onSuccess {
                                        endpoint = endpointDraft.trim()
                                        preferences.edit().putString("market_url", endpoint).apply()
                                        catalogue = emptyList()
                                        notice = "市场地址已保存。"
                                        error = null
                                    }.onFailure { error = it.message }
                            }, enabled = !busy) { Text("保存地址") }
                        }
                        item {
                            Text("安装与恢复", style = MaterialTheme.typography.titleMedium)
                            HostCrashRecovery.diagnostics(appContext).forEach { message -> Text(message, style = MaterialTheme.typography.bodySmall) }
                            if (HostCrashRecovery.isSafeMode(appContext)) {
                                OutlinedButton(onClick = {
                                    execute("正在退出安全模式…") {
                                        HostCrashRecovery.leaveSafeMode(appContext)
                                        withContext(Dispatchers.IO) { AhupGate.initializeBootstrap(appContext).getOrThrow() }
                                        bootstrapReady = true
                                    }
                                }, enabled = !busy) { Text("安装修复版本后，重试初始化") }
                            }
                            Text("基础插件安装失败时可重试。插件升级后如无法打开，可恢复上一正常版本；数据版本不兼容时会保留数据并停止回退。",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                execute("正在修复基础组件…") {
                                    withContext(Dispatchers.IO) { AhupGate.initializeBootstrap(appContext).getOrThrow() }
                                    bootstrapReady = true
                                    notice = "基础插件检查完成。"
                                    if (!initialized) tab = "setup"
                                }
                            }, enabled = !busy) { Text("检查并恢复基础插件") }
                            OutlinedButton(onClick = { directoryPicker.launch(null) }, enabled = !busy) { Text("从目录重新安装插件") }
                        }
                        items(installed, key = { it.id }) { manifest ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(manifest.title)
                                    Text("${manifest.id} · ${manifest.versionName}", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    execute("正在恢复 ${manifest.title}…") {
                                        val restored = withContext(Dispatchers.IO) { AhupInstaller.restorePrevious(appContext, manifest.id) }
                                        notice = if (restored) "已恢复上一正常版本，请关闭后重新启动应用。" else "没有可恢复的上一正常版本。"
                                    }
                                }, enabled = !busy) { Text("恢复") }
                            }
                            if (HostCrashRecovery.isQuarantined(appContext, manifest.id, manifest.versionCode)) {
                                Text("此版本已隔离，自动启动已阻止。", color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = {
                                    confirmation = ShellConfirmation("重试隔离版本？", "尚未修复的代码可能再次异常退出。原有用户数据会保留。", "重试一次") {
                                        execute("正在解除此版本隔离…") {
                                            HostCrashRecovery.allowRetry(appContext, manifest.id, manifest.versionCode)
                                            HostCrashRecovery.leaveSafeMode(appContext)
                                            withContext(Dispatchers.IO) { AhupGate.initializeBootstrap(appContext).getOrThrow() }
                                            bootstrapReady = true
                                        }
                                    }
                                }, enabled = !busy) { Text("手动重试此版本") }
                            }
                            if (manifest.homeCard) {
                                val manager = PluginServices.resolve(PluginHomeCardManager::class.java)
                                var visible by remember(manifest.id, generation) { mutableStateOf(manager?.isVisible(manifest.id) != false) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = visible, onCheckedChange = {
                                        manager?.setVisible(manifest.id, it)
                                        visible = it
                                    }, enabled = manager != null && !busy)
                                    Text("在首页显示功能卡片", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { moveHomeCard(manifest.id, -1) }, enabled = !busy) { Text("上移") }
                                    TextButton(onClick = { moveHomeCard(manifest.id, 1) }, enabled = !busy) { Text("下移") }
                                }
                            }
                        }
                        if (RuntimePluginLoader.lastErrors.isNotEmpty()) item {
                            Text("加载诊断", style = MaterialTheme.typography.titleMedium)
                            Text(RuntimePluginLoader.lastErrors.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        confirmation?.let { request ->
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(request.title) },
                text = { Text(request.message) }, confirmButton = {
                    TextButton(onClick = { confirmation = null; request.action() }) { Text(request.confirm) }
                }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
        }
    }
}

private data class ShellConfirmation(val title: String, val message: String, val confirm: String, val action: () -> Unit)

@Composable
private fun FeatureChoice(manifest: AhupManifest, checked: Boolean, required: Boolean, enabled: Boolean,
    status: String, onChecked: (Boolean) -> Unit, onUninstall: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled && !required)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(manifest.title + if (required) " · 必需" else "", style = MaterialTheme.typography.titleSmall)
                Text(manifest.summary, style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (onUninstall != null) TextButton(onClick = onUninstall, enabled = enabled) { Text("卸载") }
        }
    }
}

@Composable
private fun PluginEntryPage(context: Context, id: String, plugin: AhuPlugin?, navigation: PluginNavigation,
    onClose: () -> Unit, onRepair: () -> Unit, showHeader: Boolean = false, suggestion: @Composable () -> Unit) {
    var ready by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val marker = remember(id, plugin?.meta?.version) { HostCrashRecovery.markPresented(id, "entry") }
    DisposableEffect(marker) { onDispose { HostCrashRecovery.finishPresentation(marker) } }
    val generation by AccountGeneration.changes.collectAsState()
    val owner = remember(id, if (id == "account") 0L else generation) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    BackHandler(onBack = onClose)
    LaunchedEffect(id) {
        runCatching { withContext(Dispatchers.IO) { RuntimePluginLoader.ensureLoaded(context, id) } }
            .onSuccess { ready = true }.onFailure { error = it.message ?: "插件加载失败" }
    }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showHeader) Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("返回安大通") }
                    Text(plugin?.meta?.title ?: id, style = MaterialTheme.typography.titleSmall)
                }
                if (showHeader) HorizontalDivider()
                suggestion()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val failure = error
                    when {
                        failure != null -> Column(modifier = Modifier.padding(24.dp)) {
                            Text(failure, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onRepair) { Text("打开修复设置") }
                        }
                        !ready -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        plugin == null -> Text("该插件未启用，请返回功能管理。", modifier = Modifier.padding(24.dp))
                        else -> CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalPluginNavigation provides navigation) {
                            plugin.Entry(remember(plugin, context) { PluginHostServicesImpl(context.applicationContext, plugin, onClose) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginHomeCard(context: Context, plugin: AhuPlugin, onOpen: () -> Unit) {
    val marker = remember(plugin.meta.id, plugin.meta.version) { HostCrashRecovery.markPresented(plugin.meta.id, "home") }
    DisposableEffect(marker) { onDispose { HostCrashRecovery.finishPresentation(marker) } }
    var contribution by remember(plugin.meta.id) { mutableStateOf<PluginContributions?>(null) }
    var resources by remember(plugin.meta.id) { mutableStateOf<Context?>(null) }
    val owner = remember(plugin.meta.id) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    LaunchedEffect(plugin.meta.id) {
        runCatching {
            withContext(Dispatchers.IO) {
                RuntimePluginLoader.ensureLoaded(context, plugin.meta.id) as? PluginContributions
            }
        }.onSuccess {
            contribution = it
            resources = RuntimePluginLoader.resourceContext(context, plugin.meta.id)
        }
    }
    val implementation = contribution
    val resourceContext = resources
    if (implementation != null && resourceContext != null) {
        CompositionLocalProvider(LocalContext provides resourceContext, LocalViewModelStoreOwner provides owner) {
            implementation.HomeCard(remember(plugin, context) { PluginHostServicesImpl(context.applicationContext, plugin) }, onOpen)
        }
    }
}

@Composable
private fun PluginForegroundContent(context: Context, plugin: AhuPlugin, onNavigate: (String) -> Unit) {
    val marker = remember(plugin.meta.id, plugin.meta.version) { HostCrashRecovery.markPresented(plugin.meta.id, "foreground") }
    DisposableEffect(marker) { onDispose { HostCrashRecovery.finishPresentation(marker) } }
    var implementation by remember(plugin.meta.id, plugin.meta.version) { mutableStateOf<PluginContributions?>(null) }
    var resources by remember(plugin.meta.id, plugin.meta.version) { mutableStateOf<Context?>(null) }
    val owner = remember(plugin.meta.id) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    LaunchedEffect(plugin.meta.id, plugin.meta.version) {
        runCatching { withContext(Dispatchers.IO) {
            val instance = RuntimePluginLoader.ensureLoaded(context, plugin.meta.id)
            (instance as? PluginContributions) to RuntimePluginLoader.resourceContext(context, plugin.meta.id)
        } }.onSuccess { (instance, resourceContext) -> implementation = instance; resources = resourceContext }
    }
    val contribution = implementation
    val resourceContext = resources
    if (contribution != null && resourceContext != null) {
        val navigation = object : PluginNavigation {
            override val route = plugin.meta.id
            override fun open(route: String) = onNavigate(route)
            override fun back() = Unit
        }
        CompositionLocalProvider(LocalContext provides resourceContext, LocalViewModelStoreOwner provides owner,
            LocalPluginNavigation provides navigation) {
            contribution.Foreground(remember(plugin, context) { PluginHostServicesImpl(context.applicationContext, plugin) })
        }
    }
}

private fun pluginFilesInDirectory(context: Context, tree: Uri): List<Uri> {
    val parent = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                if (cursor.getString(1).endsWith(".ahup", ignoreCase = true))
                    add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
            }
        }
    }.orEmpty()
}

private fun formatBytes(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / (1024.0 * 1024))
    else "${bytes / 1024} KB"

/** The batch owns inspected temporary files even if a later archive fails or the user cancels. */
private suspend fun <T> withCandidateBatch(block: suspend (MutableList<AhupCandidate>) -> T): T {
    val candidates = mutableListOf<AhupCandidate>()
    return try { block(candidates) } finally {
        withContext(NonCancellable + Dispatchers.IO) { candidates.forEach { it.close() } }
    }
}
