package com.ahu.ahutong.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ahu.ahutong.core.plugin.PluginNotificationScheduler
import com.ahu.ahutong.core.plugin.PluginReminder
import com.ahu.ahutong.core.plugin.PluginReminderRenderer
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.RuntimePluginLoader
import org.json.JSONArray
import org.json.JSONObject

/** Persistent declarative reminders. Android never needs a plugin class to receive an alarm. */
class HostPluginNotifications(private val context: Context) : PluginNotificationScheduler {
    private val preferences = context.getSharedPreferences("ahup_reminder_plans", Context.MODE_PRIVATE)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    override fun replace(pluginId: String, reminders: List<PluginReminder>) = synchronized(lock) {
        requireId(pluginId)
        require(reminders.size <= 500 && reminders.map { it.key }.distinct().size == reminders.size)
        val old = plans(pluginId)
        old.forEach { cancelAlarm(pluginId, it.getString("key")) }
        val now = System.currentTimeMillis()
        val records = if (AhupGate.isFeatureEnabled(context, pluginId)) reminders.filter { it.triggerAtMillis > now }
            .map { reminder ->
                require(reminder.key.isNotBlank() && reminder.key.length <= 200)
                JSONObject().put("key", reminder.key).put("at", reminder.triggerAtMillis)
                    .put("title", reminder.title.take(200)).put("content", reminder.content.take(4000))
                    .put("route", reminder.route).put("account", SessionStore.currentUser()?.xh.orEmpty())
                    .put("extraJson", reminder.extraJson).put("expiresAt", reminder.expiresAtMillis)
            } else emptyList()
        check(preferences.edit().putString(pluginId, JSONArray(records).toString()).commit())
        records.forEach { schedule(pluginId, it) }
    }

    override fun cancel(pluginId: String) = synchronized(lock) {
        requireId(pluginId)
        plans(pluginId).forEach { cancelAlarm(pluginId, it.getString("key")) }
        check(preferences.edit().remove(pluginId).commit())
        notifications.activeNotifications.filter { it.tag?.startsWith("ahup:$pluginId:") == true }
            .forEach { notifications.cancel(it.tag, it.id) }
    }

    override fun notify(pluginId: String, title: String, content: String, route: String) {
        requireId(pluginId)
        display(pluginId, "immediate", title, content, route)
    }

    fun restore() {
        if (!AhupGate.ensureBackgroundReady(context)) return
        synchronized(lock) {
        preferences.all.keys.toList().forEach { id ->
            if (!AhupGate.isFeatureEnabled(context, id)) cancel(id)
            else plans(id).filter { it.optString("account") == SessionStore.currentUser()?.xh.orEmpty() }
                .filter { it.getLong("at") > System.currentTimeMillis() }.forEach { schedule(id, it) }
        }
        }
    }

    fun deliver(pluginId: String, key: String) {
        if (!AhupGate.ensureBackgroundReady(context)) return
        if (!AhupGate.isFeatureEnabled(context, pluginId)) { cancel(pluginId); return }
        // Plugin startup can schedule reminders itself. Never call it while owning the plan lock.
        val record = synchronized(lock) { plans(pluginId).firstOrNull { it.getString("key") == key } } ?: return
        val observedGeneration = AccountGeneration.current
        val expiresAt = if (record.has("expiresAt") && !record.isNull("expiresAt")) record.getLong("expiresAt") else null
        if (record.optString("account") == SessionStore.currentUser()?.xh.orEmpty() &&
            (expiresAt == null || System.currentTimeMillis() < expiresAt)) {
            // Stable tag makes a retried delivery replace the same notification.
            val reminder = PluginReminder(key, record.getLong("at"), record.getString("title"),
                record.getString("content"), record.optString("route"),
                if (record.has("extraJson") && !record.isNull("extraJson")) record.getString("extraJson") else null, expiresAt)
            val custom = runCatching {
                (RuntimePluginLoader.ensureLoaded(context, pluginId) as? PluginReminderRenderer)?.buildNotification(context, reminder)
            }.getOrNull()
            runCatching { AccountGeneration.withCurrent(observedGeneration) {
                if (record.optString("account") != SessionStore.currentUser()?.xh.orEmpty() ||
                    !AhupGate.isFeatureEnabled(context, pluginId)) return@withCurrent
                if (custom != null && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                    notifications.notify("ahup:$pluginId:$key", 0, custom)
                } else display(pluginId, key, reminder.title, reminder.content, reminder.route)
            } }
        }
        synchronized(lock) {
            val latest = plans(pluginId)
            // A concurrent account switch or replan must not be overwritten by an old delivery.
            val remaining = latest.filterNot { it.getString("key") == key && it.toString() == record.toString() }
            check(preferences.edit().putString(pluginId, JSONArray(remaining).toString()).commit())
        }
    }

    private fun display(id: String, key: String, title: String, content: String, route: String) {
        if (!AhupGate.isFeatureEnabled(context, id)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val channel = "ahup_$id"
        notifications.createNotificationChannel(NotificationChannel(channel, "安大通 · $id", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .setData(Uri.Builder().scheme("ahutong").authority("plugin").appendPath(id).appendQueryParameter("route", route).build())
            .putExtra("plugin_id", id).putExtra("plugin_route", route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content)).setContentIntent(pending).setAutoCancel(true).build()
        notifications.notify("ahup:$id:$key", 0, notification)
    }

    private fun schedule(id: String, record: JSONObject) {
        val pending = pending(id, record.getString("key"), PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.getLong("at"), pending)
        } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.getLong("at"), pending)
    }

    private fun cancelAlarm(id: String, key: String) {
        pending(id, key, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    private fun pending(id: String, key: String, flags: Int): PendingIntent? {
        val intent = Intent(context, PluginReminderReceiver::class.java)
            .setData(Uri.Builder().scheme("ahutong-reminder").authority(id).appendPath(key).build())
            .putExtra("plugin", id).putExtra("key", key)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun plans(id: String): List<JSONObject> {
        val list = JSONArray(preferences.getString(id, "[]"))
        return List(list.length()) { list.getJSONObject(it) }
    }

    private fun requireId(id: String) { require(id.matches(Regex("[a-z0-9_]+"))) }
    companion object { private val lock = Any() }
}

class PluginReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("plugin") ?: return
        val key = intent.getStringExtra("key") ?: return
        if (!id.matches(Regex("[a-z0-9_]+"))) return
        val pending = goAsync()
        java.util.concurrent.Executors.newSingleThreadExecutor().apply {
            execute { try { HostPluginNotifications(context).deliver(id, key) } finally { pending.finish(); shutdown() } }
        }
    }
}

class PluginReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) {
            val pending = goAsync()
            java.util.concurrent.Executors.newSingleThreadExecutor().apply {
                execute { try { HostPluginNotifications(context).restore() } finally { pending.finish(); shutdown() } }
            }
        }
    }
}
