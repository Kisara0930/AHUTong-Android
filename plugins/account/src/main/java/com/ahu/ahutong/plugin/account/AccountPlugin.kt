package com.ahu.ahutong.plugin.account

import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.*
import com.ahu.ahutong.plugin.account.ui.Login
import com.ahu.ahutong.plugin.account.ui.LoginViewModel
import com.ahu.ahutong.plugin.account.ui.SavedAccountsDialog

class AccountPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("account", "账户与认证", "校园账户、身份认证与账号管理",
        PluginIcon.Bytes(byteArrayOf()), 0xff4263eb, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var session: AccountSession

    override fun onCreate(environment: PluginEnvironment) {
        session = AccountSession()
        environment.services.publish(CampusSessionService::class.java, session)
        environment.services.publish(AhuSession::class.java, session)
        environment.services.publish(SessionIdentity::class.java, SessionStore)
    }

    @Composable override fun Entry(host: PluginHostServices) {
        val status by session.state.collectAsState()
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        val route = navigation?.route.orEmpty()
        val user = remember(status, generation) { SessionStore.currentUser() }
        var revision by remember { mutableIntStateOf(0) }
        if (user == null || status == AhuSessionState.Status.Expired || route.substringBefore('?') == "login") {
            val factory = remember(session) { object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(session) as T
            } }
            val model: LoginViewModel = viewModel(key = "account.login", factory = factory)
            val savedId = remember(route) { runCatching { Uri.parse(route).getQueryParameter("account") }.getOrNull().orEmpty() }
            val savedPassword = remember(savedId) { if (savedId.isBlank()) "" else SavedAccounts.password(savedId).orEmpty() }
            key(navigation?.requestId) {
                Login(loginViewModel = model, initialUserId = savedId, initialPassword = savedPassword,
                    autoLogin = savedId.isNotBlank() && savedPassword.isNotBlank(),
                    onBack = if (user != null) ({ attempted -> if (!attempted) (host as? PluginHostV2)?.close() }) else null,
                    onLoggedIn = { navigation?.open("home") })
            }
        } else {
            SavedAccountsDialog(accounts = remember(revision) { SavedAccounts.accounts() }, currentUserId = user.xh,
                onSelect = { id -> navigation?.open(Uri.Builder().path("login").appendQueryParameter("account", id).build().toString()) },
                onForget = { id -> SavedAccounts.forget(id); revision++ },
                onAdd = { navigation?.open("login") }, onDismiss = { (host as? PluginHostV2)?.close() })
        }
    }
}
