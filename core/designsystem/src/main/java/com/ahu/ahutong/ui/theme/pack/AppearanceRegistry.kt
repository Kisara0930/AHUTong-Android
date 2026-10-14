package com.ahu.ahutong.ui.theme.pack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.ui.theme.AhuThemeConfig

/** Shared ABI only. Optional appearance implementations live in their AHUP. */
interface AppearanceProvider {
    fun pack(source: SlotSource): AppComponentPack?
    @Composable fun Wrap(config: AhuThemeConfig, content: @Composable () -> Unit)
}

object AppearanceRegistry {
    var provider: AppearanceProvider? by mutableStateOf(null)
        private set
    var preferences: AhuThemeConfig? by mutableStateOf(null)
        private set
    var lastHostConfig: AhuThemeConfig? = null
        private set

    fun register(owner: AppearanceProvider) { provider = owner }
    fun unregister(owner: AppearanceProvider) {
        if (provider === owner) { provider = null; preferences = null }
    }
    fun update(owner: AppearanceProvider, config: AhuThemeConfig) {
        if (provider === owner) preferences = config
    }
    fun resolve(source: SlotSource): AppComponentPack =
        if (source == SlotSource.MATERIAL) MaterialComponentPack else provider?.pack(source) ?: MaterialComponentPack

    fun effective(host: AhuThemeConfig): AhuThemeConfig {
        if (host.isPreferenceReady) lastHostConfig = host
        val requested = preferences ?: host
        return if (provider != null) requested else requested.copy(
            appUiTheme = AppUiTheme.MATERIAL, componentSlotOverrides = emptyMap()
        )
    }
}
