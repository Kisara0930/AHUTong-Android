package com.ahu.ahutong

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ahu.ahutong.data.model.AppThemeMode
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.data.model.DEFAULT_THEME_COLOR
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.PluginHostLauncherHub
import com.ahu.ahutong.ui.plugin.PluginRegistry
import com.ahu.ahutong.ui.shell.PluginShell
import com.ahu.ahutong.ui.theme.AHUTheme
import com.ahu.ahutong.ui.theme.AhuThemeConfig
import com.ahu.ahutong.ui.theme.pack.parseSlotOverrides
import com.ahu.ahutong.platform.HostNavigationRequests
import com.ahu.ahutong.platform.HostSettings
import com.ahu.ahutong.platform.LegacyActionDestinations
import com.ahu.ahutong.ui.shell.OriginalAppPresentation
import kotlinx.coroutines.launch

class PluginActivity : ComponentActivity() {
    private var requestedPluginId by mutableStateOf<String?>(null)
    private var requestedRoute by mutableStateOf("")
    private var requestedRequestId by mutableLongStateOf(0L)
    private var cameraResult: ((Boolean) -> Unit)? = null
    private var imageResult: ((Uri?) -> Unit)? = null
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraResult?.invoke(it); cameraResult = null
    }
    private val image = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        imageResult?.invoke(it); imageResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readRequest(intent)
        PluginHostLauncherHub.attach(this, { callback ->
            runOnUiThread { cameraResult?.invoke(false); cameraResult = callback; camera.launch(Manifest.permission.CAMERA) }
        }, { callback ->
            runOnUiThread { imageResult?.invoke(null); imageResult = callback; image.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        })
        val app = application as PluginApplication
        setContent {
            val preferences = app.preferences
            val startup = remember { preferences.getStartupThemePreferences() }
            val theme by preferences.appUiTheme.collectAsState(startup?.appUiTheme ?: AppUiTheme.MATERIAL)
            val color by preferences.themeColor.collectAsState(startup?.themeColor ?: DEFAULT_THEME_COLOR)
            val mode by preferences.themeMode.collectAsState(startup?.themeMode ?: AppThemeMode.FOLLOW_SYSTEM)
            val overrides by preferences.componentSlotOverrides.collectAsState(startup?.slotOverrides.orEmpty())
            val plugins by PluginRegistry.plugins.collectAsState()
            val navigation by HostNavigationRequests.pending.collectAsState()
            LaunchedEffect(navigation?.sequence) {
                navigation?.let {
                    requestedPluginId = it.pluginId
                    requestedRoute = it.route
                    requestedRequestId++
                    HostNavigationRequests.consume(it.sequence)
                }
            }
            LaunchedEffect(theme, color, mode) { preferences.rememberStartupThemePreferences(theme, color, mode) }
            val initialized = AhupGate.isInitialized(this)
            LaunchedEffect(plugins.map { it.meta.id to it.meta.version }, initialized) { app.activateSelected() }
            AHUTheme(AhuThemeConfig(theme, color, mode, true, parseSlotOverrides(overrides))) {
                OriginalAppPresentation {
                PluginShell(
                    requestedPluginId = requestedPluginId,
                    requestedRoute = requestedRoute,
                    onRequestConsumed = { requestedPluginId = null; requestedRoute = "" },
                    requestedRequestId = requestedRequestId,
                    hostSettings = { endpoint, navigate, manage -> HostSettings(endpoint, navigate, manage) },
                    resolveSuggestionDestination = LegacyActionDestinations::target
                )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readRequest(intent) }
    override fun onStart() { super.onStart(); (application as PluginApplication).setForeground(true) }
    override fun onStop() { (application as PluginApplication).setForeground(false); super.onStop() }
    override fun onDestroy() {
        PluginHostLauncherHub.detach()
        cameraResult?.invoke(false); imageResult?.invoke(null)
        cameraResult = null; imageResult = null
        super.onDestroy()
    }
    private fun readRequest(intent: Intent) {
        requestedPluginId = intent.getStringExtra("plugin_id")?.takeIf { it.matches(Regex("[a-z0-9_]+")) }
        requestedRoute = intent.getStringExtra("plugin_route") ?: intent.getStringExtra("route").orEmpty()
        if (intent.action == "com.ahu.ahutong.action.OPEN_CAMPUS_NOTICE") {
            val account = intent.getStringExtra("campus_notice_account").orEmpty()
            val article = intent.getStringExtra("campus_notice_article").orEmpty()
            val url = intent.getStringExtra("campus_notice_url").orEmpty()
            if (account.length in 1..200 && article.length in 1..500 && url.length in 1..8192) {
                // Identity and the stored article URL are checked by the plugin after bootstrap.
                // Reading credentials here would run before the strict storage migration gate.
                requestedPluginId = "campus_notices"
                requestedRoute = Uri.Builder().path("article").appendQueryParameter("account", account)
                    .appendQueryParameter("id", article).appendQueryParameter("url", url).build().toString()
            }
        }
        requestedRequestId++
    }
}
