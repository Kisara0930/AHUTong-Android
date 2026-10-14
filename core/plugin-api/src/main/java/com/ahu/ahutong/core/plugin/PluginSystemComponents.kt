package com.ahu.ahutong.core.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

interface PluginWidgetProvider {
    /** Must only read cached facts. Widget rendering never signs in or refreshes business data. */
    fun renderWidget(hostContext: Context, appWidgetId: Int, options: Bundle): RemoteViews
}

interface PluginWidgetService { fun update(pluginId: String) }

interface PluginHomeCardManager {
    fun isVisible(id: String): Boolean
    fun setVisible(id: String, visible: Boolean): Boolean
}

interface PluginComponentLauncher {
    fun openIsolatedWeb(pluginId: String, route: String, extras: Bundle)
}

interface PluginIsolatedWebHandler {
    fun createController(activity: Activity, route: String): PluginActivityController
}

interface PluginActivityController {
    fun onCreate(savedState: Bundle?)
    fun onDestroy() {}
    fun onNewIntent(intent: Intent) {}
}

interface PluginContentCallHandler {
    fun call(method: String, arg: String?, extras: Bundle?): Bundle
}
