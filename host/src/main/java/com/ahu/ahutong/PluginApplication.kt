package com.ahu.ahutong

import android.app.Application
import android.os.Build
import android.webkit.WebView
import com.ahu.ahutong.appwidget.HostWidgetService
import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.core.common.UserNoticeHolder
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.core.storage.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.platform.*
import com.ahu.ahutong.ui.plugin.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import org.json.JSONObject
import com.ahu.ahutong.personalization.runtime.PersonalizationFeatureAccess

/** Stable shell: constructing the Application never initializes a business component. */
class PluginApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var preferences: HostPreferences
        private set
    val activationError = MutableStateFlow<String?>(null)
    private var foreground = false
    private val activationMutex = kotlinx.coroutines.sync.Mutex()

    override fun onCreate() {
        super.onCreate()
        AppEnvironmentHolder.install(AndroidAppEnvironment(this))
        UserNoticeHolder.install(ToastUserNotice)
        HostCrashRecovery.install(this)
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName
        if (Build.VERSION.SDK_INT >= 28 && process != packageName) {
            WebView.setDataDirectorySuffix(process.substringAfter(':'))
        }
        val hostServices = PluginServices.forPlugin("__host")
        hostServices.publish(PluginComponentLauncher::class.java, HostComponentLauncher(this))
        PluginBootstrapHooks.handler = ::migrateLegacyStorage
        if (process != packageName) return

        preferences = HostPreferences(this)
        hostServices.publish(SettingsStore::class.java, preferences)
        hostServices.publish(CourseReminderSettings::class.java, preferences)
        hostServices.publish(PaymentKeyboardSetting::class.java, preferences)
        hostServices.publish(PluginNotificationScheduler::class.java, HostPluginNotifications(this))
        hostServices.publish(PluginWorkScheduler::class.java, HostPluginWork(this))
        hostServices.publish(PluginWidgetService::class.java, HostWidgetService(this))
        hostServices.publish(PluginHomeCardManager::class.java, HostHomeCards(this))
        hostServices.publish(PersonalizationFeatureAccess::class.java, HostFeatureAccess(this))

        scope.launch {
            AccountGeneration.changes.drop(1).collect {
                if (AhupGate.isInitialized(this@PluginApplication)) {
                    withContext(Dispatchers.IO) {
                        AhupInstaller.installedIds(this@PluginApplication).forEach { id ->
                            HostPluginWork(this@PluginApplication).cancelAll(id)
                            HostPluginNotifications(this@PluginApplication).cancel(id)
                        }
                    }
                    RuntimePluginLoader.notifyAccountChanged()
                    HostWidgetService(this@PluginApplication).update("schedule")
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        RuntimePluginLoader.notifyForeground(value)
        if (value) activateSelected()
    }

    fun activateSelected() {
        scope.launch {
            if (!AhupGate.isBootstrapValidated(this@PluginApplication) ||
                !AhupGate.isInitialized(this@PluginApplication) || !activationMutex.tryLock()) return@launch
            try {
                withContext(Dispatchers.IO) {
                    AhupInstaller.installedIds(this@PluginApplication).forEach { id ->
                        val manifest = AhupInstaller.manifestOf(this@PluginApplication, id)
                        if (manifest?.autoStart == true && AhupGate.isFeatureEnabled(this@PluginApplication, id)) {
                            runCatching { RuntimePluginLoader.ensureLoaded(this@PluginApplication, id) }
                                .onFailure { activationError.value = "$id：${it.message ?: "加载失败"}" }
                        }
                    }
                }
                RuntimePluginLoader.notifyForeground(foreground)
            } finally { activationMutex.unlock() }
        }
    }

    private fun migrateLegacyStorage(context: android.content.Context) {
        val legacy = File(filesDir, "rust-sdk")
        val state = File(filesDir, "ahup_migrations/guixu-v1/state.json")
        val incomplete = state.exists() && runCatching {
            JSONObject(state.readText()).optString("phase") != "complete"
        }.getOrDefault(true)
        if (!legacy.exists() && !incomplete) return
        val id = "storage_migration"
        if (AhupInstaller.manifestOf(context, id) == null) {
            assets.open("migrations/storage_migration.ahup").use { input ->
                val candidate = AhupInstaller.inspect(context, input)
                try { AhupInstaller.install(context, candidate) } finally { candidate.close() }
            }
        }
        AhupInstaller.setEnabled(context, id, true)
        // onCreate is transactional and throws on an incomplete/invalid migration.
        RuntimePluginLoader.ensureLoaded(context, id)
        check(state.exists() && JSONObject(state.readText()).optString("phase") == "complete") {
            "旧数据迁移尚未完成，原数据已保留"
        }
        AhupInstaller.setEnabled(context, id, false)
    }
}
