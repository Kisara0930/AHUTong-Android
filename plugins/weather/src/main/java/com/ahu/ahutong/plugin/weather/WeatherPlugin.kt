package com.ahu.ahutong.plugin.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginContributions
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginHostV2
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginMeta
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginLifecycle
import com.ahu.ahutong.core.plugin.PluginServiceRegistry
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder

class WeatherPlugin : AhuPlugin, PluginContributions, PluginLifecycle {
    override val meta = PluginMeta("weather", "天气", "城市天气、预报、空气质量与首页天气",
        PluginIcon.Resource(0), 0xFF607DB8, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private var services: PluginServiceRegistry? = null
    override fun onCreate(environment: PluginEnvironment) { services = environment.services }
    override fun onDestroy() { services = null }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val factory = remember(host) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = WeatherViewModel(host) {
                    services?.resolve(BehaviorRecorder::class.java)
                } as T
            }
        }
        val model: WeatherViewModel = viewModel(key = "plugin.weather", factory = factory)
        Weather(model, onBack = { (host as? PluginHostV2)?.close() })
    }

    @Composable
    override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        val api = remember(host) { WeatherApi.create(host) }
        HomeWeatherWidget(onClick = onOpen, api = api, config = WeatherHomeConfig.fromCache(host.storage()), mode = WeatherHomeMode.Compact)
    }
}
