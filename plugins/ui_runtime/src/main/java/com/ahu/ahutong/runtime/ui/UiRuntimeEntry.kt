package com.ahu.ahutong.runtime.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.ahu.ahutong.PluginActivity
import com.ahu.ahutong.hostapi.HostEnvironment
import com.ahu.ahutong.hostapi.HostPlugin
import com.ahu.ahutong.runtime.KernelBindings
import com.ahu.ahutong.platform.HostPluginWork
import com.ahu.ahutong.core.plugin.PluginWorkNetwork

/** Original presentation lives here; the native APK sees only a View and Android values. */
class UiRuntimeEntry : HostPlugin {
    override fun initialize(environment: HostEnvironment) = KernelBindings.install(environment)
    override fun createView(activity: Activity, request: Bundle): View =
        (activity as? PluginActivity)?.createRuntimeView() ?: error("需要已初始化的安大通页面容器")

    override fun dispatch(context: Context, operation: String, values: Bundle, reply: HostPlugin.Reply): Boolean {
        val scheduler = HostPluginWork(context)
        val owner = values.getString("owner") ?: return false
        val task = values.getString("task") ?: return false
        when (operation) {
            "schedule" -> scheduler.enqueue(owner, task, "native:$task", values.getLong("delay"),
                values.getLong("period").takeIf { it > 0 },
                if (values.getBoolean("network")) PluginWorkNetwork.CONNECTED else PluginWorkNetwork.NONE,
                false, false, false, true, 30_000)
            "cancel" -> scheduler.cancel(owner, "native:$task")
            else -> return false
        }
        reply.complete(Bundle())
        return true
    }
}
