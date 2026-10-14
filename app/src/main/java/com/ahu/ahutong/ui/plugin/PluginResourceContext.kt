package com.ahu.ahutong.ui.plugin

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources

/** Resource replacement must not remove the Activity from the base-context chain. */
internal class PluginResourceContext(
    base: Context,
    private val pluginResources: Resources,
    private val pluginClassLoader: ClassLoader
) : ContextWrapper(base) {
    override fun getResources(): Resources = pluginResources
    override fun getAssets(): AssetManager = pluginResources.assets
    override fun getClassLoader(): ClassLoader = pluginClassLoader
    override fun getApplicationContext(): Context = baseContext.applicationContext

    @Suppress("DEPRECATION")
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        val configuration = Configuration(pluginResources.configuration).apply { updateFrom(overrideConfiguration) }
        val configured = if (pluginResources is PluginResources) pluginResources.withConfiguration(configuration)
            else Resources(pluginResources.assets, pluginResources.displayMetrics, configuration)
        // Deliberately retain baseContext, rather than replacing it with a ContextImpl returned by Android.
        return PluginResourceContext(baseContext, configured, pluginClassLoader)
    }
}
