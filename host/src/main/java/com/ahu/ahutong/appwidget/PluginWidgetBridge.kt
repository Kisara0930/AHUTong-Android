package com.ahu.ahutong.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.ahu.ahutong.core.plugin.PluginWidgetProvider
import com.ahu.ahutong.core.plugin.PluginWidgetService
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.RuntimePluginLoader

open class ScheduleAdaptiveWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        java.util.concurrent.Executors.newSingleThreadExecutor().apply {
            execute { try { appWidgetIds.forEach { render(context, manager, it) } } finally { pending.finish(); shutdown() } }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.ahu.ahutong.appwidget.ACTION_REFRESH_SCHEDULE") {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)))
        } else super.onReceive(context, intent)
    }
    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val views = runCatching {
            check(AhupGate.ensureBackgroundReady(context))
            check(AhupGate.isFeatureEnabled(context, "schedule"))
            val provider = RuntimePluginLoader.ensureLoaded(context, "schedule") as PluginWidgetProvider
            provider.renderWidget(context.applicationContext, id, manager.getAppWidgetOptions(id))
        }.getOrElse {
            val layout = context.resources.getIdentifier("host_widget_unavailable", "layout", context.packageName)
            val label = context.resources.getIdentifier("host_widget_message", "id", context.packageName)
            RemoteViews(context.packageName, layout).apply {
                setTextViewText(label, "课表功能尚未就绪\n点击打开安大通")
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    launch.putExtra("plugin_id", "schedule").putExtra("plugin_route", "schedule")
                    setOnClickPendingIntent(label, PendingIntent.getActivity(context, 0, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
        }
        manager.updateAppWidget(id, views)
    }
}

/** Retains the component identity used by previously pinned Glance widgets. */
class ScheduleAppWidgetReceiver : ScheduleAdaptiveWidgetProvider()

class HostWidgetService(private val context: Context) : PluginWidgetService {
    override fun update(pluginId: String) {
        if (pluginId != "schedule") return
        val manager = AppWidgetManager.getInstance(context)
        listOf(ScheduleAdaptiveWidgetProvider::class.java, ScheduleAppWidgetReceiver::class.java).forEach { provider ->
            val component = ComponentName(context, provider)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }
}
