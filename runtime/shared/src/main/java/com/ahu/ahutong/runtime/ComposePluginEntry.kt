package com.ahu.ahutong.runtime

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.hostapi.HostEnvironment
import com.ahu.ahutong.hostapi.HostPlugin
import com.ahu.ahutong.ui.plugin.*

/** Native entry returns View. Compose and the original feature contract remain SDK internals. */
abstract class ComposePluginEntry : HostPlugin {
    private lateinit var environment: HostEnvironment
    private var instance: AhuPlugin? = null
    protected abstract fun createImplementation(): AhuPlugin
    @Synchronized fun implementation(): AhuPlugin = instance ?: createImplementation().also { instance = it }
    override fun initialize(environment: HostEnvironment) { this.environment = environment }
    override fun createView(activity: Activity, request: Bundle): View {
        val plugin = implementation()
        val context = RuntimePluginLoader.resourceContext(activity, plugin.meta.id)
        return ComposeView(context).apply {
            setContent {
                val navigation = LocalPluginNavigation.current
                CompositionLocalProvider(LocalContext provides context) {
                    plugin.Entry(remember(plugin, navigation) {
                        PluginHostServicesImpl(environment.application(), plugin) { navigation?.back() }
                    })
                }
            }
        }
    }
    override fun close() { (instance as? PluginLifecycle)?.onDestroy() }
}
