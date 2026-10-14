package com.ahu.ahutong.plugin.chaoxing

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ahu.ahutong.core.plugin.PluginNotificationScheduler
import com.ahu.ahutong.core.plugin.PluginReminder
import com.ahu.ahutong.core.plugin.PluginServiceRegistry

/** Business scheduling stays here; Android delivery and reboot persistence stay in the host. */
class PluginChaoxingReminders(private val services: PluginServiceRegistry) : ChaoxingReminders {
    private fun scheduler() = checkNotNull(services.resolve(PluginNotificationScheduler::class.java)) {
        "宿主提醒服务不可用"
    }

    override fun cancelAll() = scheduler().cancel("chaoxing")
    override fun scheduleAll() {
        val reminders = ReminderPlan.build(Store.getRemindSetting(), Store.hasCookie(), Store.getWorks(),
            Store.getCustomEvents(), System.currentTimeMillis()).map {
            PluginReminder(it.key, it.triggerAtMillis, it.title, it.content, "plugin/chaoxing")
        }
        // Replace the full plan, so removed/completed work cannot leave orphan alarms.
        scheduler().replace("chaoxing", reminders)
    }
    override fun rescheduleAll() = scheduleAll()
    override fun sendTest() = scheduler().notify("chaoxing", "学习通日历", "这是一条测试通知", "plugin/chaoxing")
}

/** Cancel both historical PendingIntent identities before moving to the host scheduler. */
internal fun migrateLegacyReminders(context: Context) {
    val marker = context.getSharedPreferences("ahup_chaoxing_migration", Context.MODE_PRIVATE)
    if (marker.getBoolean("reminders_v3", false)) return
    val keys = Store.getRemindedMap().keys.toMutableSet()
    Store.getWorks().forEach { work -> work.endTs?.let { keys += "${work.workId}|$it" } }
    Store.getCustomEvents().forEach { keys += "event_${it.id}|${it.startTs}" }
    val alarms = context.getSystemService(AlarmManager::class.java)
    keys.forEach { key ->
        listOf((0x40000000 or (key.hashCode() and 0x0fffffff)) to "com.ahu.ahutong.reminder.ACTION_REMIND_XUEXIAOTONG",
            key.hashCode() to null).forEach { (requestCode, action) ->
            val intent = Intent().apply {
                component = ComponentName(context.packageName, "com.ahu.ahutong.reminder.AlarmReceiver")
                this.action = action
            }
            PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { pending ->
                alarms.cancel(pending)
                pending.cancel()
            }
        }
    }
    context.getSystemService(NotificationManager::class.java).let { manager ->
        manager.activeNotifications.filter { it.notification.channelId == "ahutong_cx_reminder" }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    Store.saveRemindedMap(emptyMap())
    marker.edit().putBoolean("reminders_v3", true).apply()
}
