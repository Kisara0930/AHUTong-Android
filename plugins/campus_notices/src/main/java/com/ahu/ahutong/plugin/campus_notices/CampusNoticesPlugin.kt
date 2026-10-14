package com.ahu.ahutong.plugin.campus_notices

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.compose.runtime.*
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.model.AcademicAccountType
import com.ahu.ahutong.data.notice.CampusNoticeRepository
import com.ahu.ahutong.data.notice.PostgraduateNoticeWebProvider
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.ui.screen.main.CampusNoticeScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine

internal object CampusNoticesRuntime {
    lateinit var environment: PluginEnvironment
    @Volatile var enabled = false
    fun isGraduate() = SessionStore.currentUser()?.academicAccountType == AcademicAccountType.POSTGRADUATE
    fun homeCards() = environment.services.resolve(PluginHomeCardManager::class.java)
    fun notifications() = environment.services.resolve(PluginNotificationScheduler::class.java)
    fun mainProcess(): Boolean {
        val context = environment.context
        val process = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        return process == context.applicationContext.packageName
    }
}

class CampusNoticesPlugin : AhuPlugin, PluginLifecycle, PluginContributions, PluginContentCallHandler {
    override val meta = PluginMeta("campus_notices", "校园通知", "本科官网、研究生院公告与未读提醒",
        PluginIcon.Resource(R.drawable.ic_campus_notice), 0xFF788B68, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var accountObserver: Job? = null
    private var presentationObserver: Job? = null
    private var webHandler: PostgraduateNoticeWebProvider? = null

    override fun onCreate(environment: PluginEnvironment) { CampusNoticesRuntime.environment = environment }
    override fun onEnable() {
        CampusNoticesRuntime.enabled = true
        if (CampusNoticesRuntime.mainProcess()) {
            presentationObserver?.cancel()
            presentationObserver = scope.launch {
                combine(CampusNoticeRepository.snapshot, SessionStore.accountIdUpdates()) { snapshot, account ->
                    val accountKey = account?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }.orEmpty()
                    org.json.JSONObject().put("account", accountKey).put("badges", org.json.JSONObject()
                        .put("campus_notices", snapshot?.takeIf { it.accountId == account }?.unreadCount ?: 0)).toString()
                }.collect { CampusNoticesRuntime.environment.storage?.putString("ui.presentation", it) }
            }
            SessionStore.currentUser()?.xh?.let(CampusNoticeRepository::open)
            CampusNoticeRepository.requestSyncIfDue()
            accountObserver?.cancel()
            accountObserver = scope.launch { AccountGeneration.changes.drop(1).collect {
                CampusNoticesRuntime.notifications()?.cancel(meta.id)
                CampusNoticeRepository.suspendWork()
                SessionStore.currentUser()?.xh?.let(CampusNoticeRepository::open)
                CampusNoticeRepository.requestSyncIfDue()
            } }
        }
    }
    override fun onDisable() {
        CampusNoticesRuntime.enabled = false
        accountObserver?.cancel()
        presentationObserver?.cancel()
        if (CampusNoticesRuntime.mainProcess()) {
            CampusNoticesRuntime.notifications()?.cancel(meta.id)
            scope.launch { CampusNoticeRepository.suspendWork() }
        }
    }
    override fun onForeground() {
        if (CampusNoticesRuntime.enabled && CampusNoticesRuntime.mainProcess()) {
            SessionStore.currentUser()?.xh?.let(CampusNoticeRepository::open)
            CampusNoticeRepository.requestSyncIfDue()
        }
    }
    override fun onBackground() {
        if (CampusNoticesRuntime.mainProcess()) scope.launch { CampusNoticeRepository.suspendWork() }
    }
    override fun onDestroy() {
        onDisable()
        scope.launch { try { if (CampusNoticesRuntime.mainProcess()) CampusNoticeRepository.suspendWork() } finally { scope.cancel() } }
    }

    @Composable override fun Entry(host: PluginHostServices) {
        val route = LocalPluginNavigation.current?.route
        LaunchedEffect(route) {
            val parsed = route?.let(Uri::parse)
            if (parsed?.path?.substringAfterLast('/') == "article") {
                val account = parsed.getQueryParameter("account")
                val article = parsed.getQueryParameter("id")
                val url = parsed.getQueryParameter("url")
                if (account != null && article != null && url != null) {
                    val verified = CampusNoticeRepository.verifiedNotice(account, article, url)
                    if (verified != null) {
                        val opened = runCatching { host.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verified.originalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                        if (opened) CampusNoticeRepository.markRead(account, article)
                    }
                }
            }
        }
        CampusNoticeScreen(onBack = { (host as? PluginHostV2)?.close() })
    }

    @Composable override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        val snapshot by CampusNoticeRepository.snapshot.collectAsState()
        val account by SessionStore.accountIdUpdates().collectAsState()
        LaunchedEffect(account) { if (account != null) CampusNoticeRepository.requestSyncIfDue() }
        val unread = snapshot?.takeIf { it.accountId == account }?.unreadCount ?: 0
        androidx.compose.material3.TextButton(onClick = onOpen) {
            androidx.compose.material3.Text(if (unread > 0) "校园通知 · $unread 条未读" else "校园通知")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!CampusNoticesRuntime.enabled || CampusNoticesRuntime.mainProcess()) return Bundle().apply { putString("error", "公告隔离进程不可用") }
        val handler = webHandler ?: PostgraduateNoticeWebProvider(CampusNoticesRuntime.environment.context).also { webHandler = it }
        return handler.call(method, arg, extras)
    }
}
