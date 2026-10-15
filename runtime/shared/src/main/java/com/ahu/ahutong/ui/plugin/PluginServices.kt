package com.ahu.ahutong.ui.plugin

import com.ahu.ahutong.core.plugin.PluginServiceRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf

/** Owner-scoped registrations can be revoked without leaking an old account or plugin instance. */
object PluginServices {
    private data class Registration(val owner: String, val service: Any)
    private val services = mutableMapOf<Class<*>, Registration>()
    private val suspended = mutableSetOf<String>()
    private var revision by mutableIntStateOf(0)

    @Synchronized
    fun <T : Any> resolve(contract: Class<T>): T? {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        return services[contract]?.takeUnless { it.owner in suspended }?.service?.let(contract::cast)
    }

    fun forPlugin(owner: String): PluginServiceRegistry = object : PluginServiceRegistry {
        override fun <T : Any> publish(contract: Class<T>, service: T) {
            require(contract.isInstance(service)) { "服务未实现共享契约" }
            synchronized(this@PluginServices) {
                val existing = services[contract]
                require(existing == null || existing.owner == owner) { "服务 ${contract.name} 已被其他插件注册" }
                services[contract] = Registration(owner, service)
                revision++
            }
        }

        override fun <T : Any> resolve(contract: Class<T>): T? = this@PluginServices.resolve(contract)
    }

    @Synchronized
    internal fun revoke(owner: String) {
        services.entries.removeAll { it.value.owner == owner }
        suspended.remove(owner)
        revision++
    }

    @Synchronized
    internal fun setEnabled(owner: String, enabled: Boolean) {
        if (enabled) suspended.remove(owner) else suspended.add(owner)
        revision++
    }
}
