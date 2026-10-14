package com.ahu.ahutong.plugin.appearance

import com.ahu.ahutong.core.plugin.PluginStorage
import com.ahu.ahutong.data.model.AppThemeMode
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.ui.theme.AhuThemeConfig
import com.ahu.ahutong.ui.theme.pack.AppearanceRegistry
import com.ahu.ahutong.ui.theme.pack.ComponentSlotId
import com.ahu.ahutong.ui.theme.pack.SlotSource
import com.ahu.ahutong.ui.theme.pack.parseSlotOverrides
import com.ahu.ahutong.ui.theme.pack.serializeSlotOverrides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The optional plugin owns preference persistence; the shared registry only holds live state. */
class AppearancePreferences(private val owner: AppearancePlugin, private val storage: PluginStorage) {
    private val initial = AppearanceRegistry.lastHostConfig
        ?: AhuThemeConfig(AppUiTheme.MATERIAL, null, AppThemeMode.FOLLOW_SYSTEM)
    private val migrated = storage.getBoolean("theme_preferences_migrated") == true
    private val _appUiTheme = MutableStateFlow(if (migrated) AppUiTheme.fromStorage(storage.getString("app_ui_theme"), null) else initial.appUiTheme)
    private val _appThemeMode = MutableStateFlow(if (migrated) AppThemeMode.fromStorage(storage.getString("app_theme_mode")) else initial.themeMode)
    private val _themeColor = MutableStateFlow(if (migrated) storage.getString("theme_color") else initial.themeColorHex)
    private val _componentSlotOverrides = MutableStateFlow(if (migrated) parseSlotOverrides(storage.getString("component_slot_overrides")) else initial.componentSlotOverrides)
    val appUiTheme = _appUiTheme.asStateFlow()
    val appThemeMode = _appThemeMode.asStateFlow()
    val themeColor = _themeColor.asStateFlow()
    val componentSlotOverrides = _componentSlotOverrides.asStateFlow()

    init { persist() }
    fun setAppUiTheme(value: AppUiTheme) { _appUiTheme.value = value; persist() }
    fun setAppThemeMode(value: AppThemeMode) { _appThemeMode.value = value; persist() }
    fun setThemeColor(value: String?) { _themeColor.value = value; persist() }
    fun clearComponentSlotOverrides() { _componentSlotOverrides.value = emptyMap(); persist() }
    fun setComponentSlotOverride(slot: ComponentSlotId, source: SlotSource?) {
        _componentSlotOverrides.value = _componentSlotOverrides.value.toMutableMap().apply {
            if (source == null) remove(slot) else put(slot, source)
        }
        persist()
    }

    private fun persist() {
        storage.putString("app_ui_theme", _appUiTheme.value.storageValue)
        storage.putString("app_theme_mode", _appThemeMode.value.storageValue)
        _themeColor.value?.let { storage.putString("theme_color", it) } ?: storage.remove("theme_color")
        storage.putString("component_slot_overrides", _componentSlotOverrides.value.serializeSlotOverrides())
        storage.putBoolean("theme_preferences_migrated", true)
        publish()
    }

    fun publish() = AppearanceRegistry.update(owner, AhuThemeConfig(_appUiTheme.value, _themeColor.value,
        _appThemeMode.value, componentSlotOverrides = _componentSlotOverrides.value))
}
