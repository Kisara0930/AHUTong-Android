package com.ahu.ahutong.core.plugin

data class PluginReminder(
    val key: String,
    val triggerAtMillis: Long,
    val title: String,
    val content: String,
    val route: String = "",
    val extraJson: String? = null,
    val expiresAtMillis: Long? = null
)

interface PluginReminderRenderer {
    /** Read-only rendering. A null result uses the host's basic notification. */
    fun buildNotification(context: android.content.Context, reminder: PluginReminder): android.app.Notification?
}

/** The host owns Android components and persists the plugin's notification plan. */
interface PluginNotificationScheduler {
    fun replace(pluginId: String, reminders: List<PluginReminder>)
    fun cancel(pluginId: String)
    fun notify(pluginId: String, title: String, content: String, route: String = "")
}

enum class PluginWorkNetwork { NONE, CONNECTED, UNMETERED }
enum class PluginWorkResult { SUCCESS, RETRY, FAILURE }

/** Implement on the plugin entry point. WorkManager never reflects plugin worker classes. */
interface PluginWorkHandler {
    suspend fun run(taskId: String): PluginWorkResult
}

interface PluginWorkScheduler {
    fun enqueue(
        pluginId: String,
        taskId: String,
        uniqueName: String,
        initialDelayMs: Long = 0,
        repeatIntervalMs: Long? = null,
        network: PluginWorkNetwork = PluginWorkNetwork.NONE,
        batteryNotLow: Boolean = false,
        storageNotLow: Boolean = false,
        charging: Boolean = false,
        replace: Boolean = false,
        backoffMs: Long = 1_800_000
    )
    fun cancel(pluginId: String, uniqueName: String)
    fun cancelAll(pluginId: String)
}
