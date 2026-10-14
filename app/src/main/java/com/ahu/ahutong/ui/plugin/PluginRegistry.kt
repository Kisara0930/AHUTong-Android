package com.ahu.ahutong.ui.plugin

import android.content.Context
import com.ahu.ahutong.core.plugin.AhuPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only signed installations enter the registry. Listing never executes business code. */
object PluginRegistry {
    private val _plugins = MutableStateFlow<List<AhuPlugin>>(emptyList())
    val plugins: StateFlow<List<AhuPlugin>> = _plugins.asStateFlow()
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        reload(context)
        initialized = true
    }

    fun reload(context: Context) {
        _plugins.value = RuntimePluginLoader.loadAll(context.applicationContext)
    }

    fun byId(id: String): AhuPlugin? = _plugins.value.firstOrNull { it.meta.id == id }
}
