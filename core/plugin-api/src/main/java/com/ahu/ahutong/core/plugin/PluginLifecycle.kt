package com.ahu.ahutong.core.plugin

import android.content.Context

/** Optional lifecycle for official plugins. Registration itself never runs plugin code. */
interface PluginLifecycle {
    fun onCreate(environment: PluginEnvironment) {}
    fun onEnable() {}
    fun onDisable() {}
    fun onAccountChanged() {}
    fun onDestroy() {}
    fun onForeground() {}
    fun onBackground() {}
}

data class PluginEnvironment(
    val context: Context,
    val pluginId: String,
    val versionCode: Int,
    val services: PluginServiceRegistry,
    val storage: PluginStorage? = null,
    val host: PluginHostServices? = null
)

/** Contracts must live in the shared SDK so all class loaders agree on their identity. */
interface PluginServiceRegistry {
    fun <T : Any> publish(contract: Class<T>, service: T)
    fun <T : Any> resolve(contract: Class<T>): T?
}
