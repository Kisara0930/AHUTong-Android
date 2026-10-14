package com.ahu.ahutong.plugin.student_mail

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.core.storage.SettingsStore
import com.ahu.ahutong.data.mail.StudentMailSession
import com.ahu.ahutong.data.mail.StudentMailWebLease
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusSessionService
import com.ahu.ahutong.ui.screen.main.StudentMailScreen
import com.ahu.ahutong.ui.screen.main.StudentMailWebActivity
import com.ahu.ahutong.ui.state.StudentMailViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop

internal object StudentMailRuntime {
    lateinit var environment: PluginEnvironment
    @Volatile var enabled = false
    fun requireEnabled() = check(enabled) { "学生邮箱组件已停用" }
    fun campus(): CampusSessionService = checkNotNull(environment.services.resolve(CampusSessionService::class.java)) { "请先启用账户组件" }
    fun launcher(): PluginComponentLauncher = checkNotNull(environment.services.resolve(PluginComponentLauncher::class.java)) { "隔离网页桥未就绪" }
    fun settings(): SettingsStore? = environment.services.resolve(SettingsStore::class.java)
    fun mainProcess(): Boolean {
        val context = environment.context
        val process = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        return process == context.applicationContext.packageName
    }
}

class StudentMailPlugin : AhuPlugin, PluginLifecycle, PluginContributions, PluginContentCallHandler, PluginIsolatedWebHandler {
    override val meta = PluginMeta("student_mail", "学生邮箱", "收发邮件、草稿、联系人、附件与隔离网页版",
        PluginIcon.Resource(R.drawable.ic_student_mail), 0xFF5789B3, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private var accountObserver: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(environment: PluginEnvironment) { StudentMailRuntime.environment = environment }
    override fun onEnable() {
        StudentMailRuntime.enabled = true
        if (StudentMailRuntime.mainProcess()) {
            accountObserver?.cancel()
            accountObserver = scope.launch { AccountGeneration.changes.drop(1).collect {
                StudentMailWebLease.clearAll()
                StudentMailSession.clear()
            } }
        }
    }
    override fun onAccountChanged() {
        if (StudentMailRuntime.mainProcess()) {
            StudentMailWebLease.clearAll()
            StudentMailSession.clear()
        }
    }
    override fun onDisable() {
        StudentMailRuntime.enabled = false
        accountObserver?.cancel()
        StudentMailWebLease.clearAll()
        StudentMailSession.suspendMemory()
    }
    override fun onDestroy() { onDisable(); scope.cancel() }

    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val account by com.ahu.ahutong.data.session.SessionStore.accountIdUpdates().collectAsState()
        key(generation, account) {
            val store = remember { ViewModelStore() }
            val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
            DisposableEffect(store) { onDispose { store.clear() } }
            val factory = remember { object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = StudentMailViewModel() as T
            } }
            val model: StudentMailViewModel = viewModel(viewModelStoreOwner = owner, key = "plugin.student_mail", factory = factory)
            StudentMailScreen(onBack = { (host as? PluginHostV2)?.close() }, vm = model)
        }
    }

    @Composable override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        androidx.compose.material3.TextButton(onClick = onOpen) { androidx.compose.material3.Text("学生邮箱") }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!StudentMailRuntime.enabled || !StudentMailRuntime.mainProcess()) return Bundle()
        return when (method) {
            StudentMailWebLease.METHOD_OPEN -> StudentMailWebLease.read(arg, true)
            StudentMailWebLease.METHOD_CHECK -> StudentMailWebLease.read(arg, false)
            StudentMailWebLease.METHOD_CLOSE -> Bundle().also { StudentMailWebLease.close(arg) }
            else -> Bundle()
        }
    }

    override fun createController(activity: Activity, route: String): PluginActivityController {
        require(route == "mailbox" && !StudentMailRuntime.mainProcess()) { "邮箱网页版必须在隔离进程启动" }
        return StudentMailWebActivity(activity as ComponentActivity)
    }
}
