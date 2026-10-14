package com.ahu.ahutong.plugin.chaoxing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginLifecycle
import com.ahu.ahutong.core.plugin.PluginMeta
import kotlinx.coroutines.Dispatchers

class ChaoxingPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("chaoxing", "学习通", "作业日历、课程进度、自建日程与提醒",
        PluginIcon.Resource(0), 0xFFE69052, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var reminders: PluginChaoxingReminders
    private var session: AppChaoxingSession? = null
    private var presentation: com.ahu.ahutong.core.plugin.PluginStorage? = null

    override fun onCreate(environment: PluginEnvironment) {
        presentation = environment.storage
        // Dedicated legacy names and Keystore alias retain credentials and caches.
        Store.init(environment.context.applicationContext)
        migrateLegacyReminders(environment.context.applicationContext)
        reminders = PluginChaoxingReminders(environment.services)
    }
    override fun onEnable() = reminders.scheduleAll()
    override fun onDisable() { session?.cancelRequests(); reminders.cancelAll() }
    override fun onDestroy() { session?.cancelRequests(); session = null }
    override fun onAccountChanged() {
        session?.clearSession()
        Store.clearLoginData()
        reminders.rescheduleAll()
    }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val navigation = com.ahu.ahutong.core.plugin.LocalPluginNavigation.current
        LaunchedEffect(navigation?.route, navigation?.requestId) {
            if (navigation?.route?.endsWith("/toggle") == true) XuexiaotongDockState.toggle()
        }
        LaunchedEffect(XuexiaotongDockState.tab) {
            presentation?.putString("ui.presentation", org.json.JSONObject().put("primaryMode",
                if (XuexiaotongDockState.tab == XuexiaotongSubTab.SCHEDULE) "schedule" else "courses").toString())
        }
        val factory = remember(host) {
            val account = session ?: AppChaoxingSession(host).also { session = it }
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = XuexiaotongViewModel(
                    account, AppChaoxingStore(), reminders, Dispatchers.IO
                ) as T
            }
        }
        val model: XuexiaotongViewModel = viewModel(key = "plugin.chaoxing", factory = factory)
        XuexiaotongScreen(model)
    }
}
