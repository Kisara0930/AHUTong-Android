package com.ahu.ahutong.plugin.appearance

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginHostV2
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginLifecycle
import com.ahu.ahutong.core.plugin.PluginMeta
import com.ahu.ahutong.core.plugin.PluginStorage
import com.ahu.ahutong.core.plugin.LocalPluginNavigation
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.data.model.DEFAULT_THEME_COLOR
import com.ahu.ahutong.ui.components.SettingsBackdropContainer
import com.ahu.ahutong.ui.components.SettingsPageLayout
import com.ahu.ahutong.ui.theme.AhuThemeConfig
import com.ahu.ahutong.ui.theme.pack.AppComponentPack
import com.ahu.ahutong.ui.theme.pack.AppearanceProvider
import com.ahu.ahutong.ui.theme.pack.AppearanceRegistry
import com.ahu.ahutong.ui.theme.pack.MaterialComponentPack
import com.ahu.ahutong.ui.theme.pack.SlotSource
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class AppearancePlugin : AhuPlugin, PluginLifecycle, AppearanceProvider {
    override val meta = PluginMeta("appearance", "外观扩展", "主题套装、组件混搭与实时预览",
        PluginIcon.Resource(0), 0xFF9B72B3, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.PLUGIN_STORAGE)
    private var preferences: AppearancePreferences? = null
    private var storage: PluginStorage? = null

    override fun onCreate(environment: PluginEnvironment) {
        AppearanceRegistry.register(this)
        storage = environment.storage
        storage?.takeIf { it.getBoolean("theme_preferences_migrated") == true }?.let {
            preferences = AppearancePreferences(this, it)
        }
    }
    override fun onEnable() { AppearanceRegistry.register(this); preferences?.publish() }
    override fun onDisable() = AppearanceRegistry.unregister(this)
    override fun onDestroy() = AppearanceRegistry.unregister(this)
    override fun pack(source: SlotSource): AppComponentPack = when (source) {
        SlotSource.MATERIAL -> MaterialComponentPack
        SlotSource.MIUIX -> MiuixComponentPack
        SlotSource.RADIANT -> RadiantComponentPack
    }

    @Composable
    override fun Wrap(config: AhuThemeConfig, content: @Composable () -> Unit) {
        SideEffect {
            // Wait for the host's legacy preferences before the one-time import.
            if (preferences == null && config.isPreferenceReady) {
                storage?.let { preferences = AppearancePreferences(this, it) }
            }
        }
        val dark = config.themeMode.resolve(isSystemInDarkTheme())
        val default = config.appUiTheme == AppUiTheme.MIUIX && config.themeColorHex == DEFAULT_THEME_COLOR
        val custom = remember(config.themeColorHex) {
            config.themeColorHex?.takeUnless { it == DEFAULT_THEME_COLOR }?.let {
                runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
            }
        }
        val key = when {
            default -> Color(0xFF3482FF)
            custom != null -> custom
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> colorResource(android.R.color.system_accent1_500)
            else -> Color(0xFF007FAC)
        }
        val system = config.themeColorHex == null || (config.themeColorHex == DEFAULT_THEME_COLOR && config.appUiTheme != AppUiTheme.MIUIX)
        val mode = when {
            default && dark -> ColorSchemeMode.Dark
            default -> ColorSchemeMode.Light
            system -> ColorSchemeMode.MonetSystem
            dark -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetLight
        }
        val controller = remember(key, dark, mode) {
            ThemeController(colorSchemeMode = mode, keyColor = key.takeUnless { system }, isDark = dark)
        }
        MiuixTheme(controller = controller) {
            Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)) { content() }
        }
    }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val model = remember(host) {
            preferences ?: AppearancePreferences(this, host.storage()).also { preferences = it }
        }
        var details by rememberSaveable { mutableStateOf(false) }
        val navigation = LocalPluginNavigation.current
        LaunchedEffect(navigation?.route, navigation?.requestId) {
            details = navigation?.route?.substringBefore('?') in setOf("settings__theme_lab", "theme_lab")
        }
        BackHandler(enabled = details) { details = false }
        if (details) ThemeLab(onBack = { details = false }, viewModel = model)
        else SettingsBackdropContainer(modifier = Modifier.fillMaxSize()) { backdrop ->
            SettingsPageLayout(title = "外观扩展", onBack = { (host as? PluginHostV2)?.close() }) {
                ThemeSettingsSection(model, backdrop, onOpenDetails = { details = true })
            }
        }
    }
}
