package com.ahu.ahutong.plugin.schedule

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ahu.ahutong.core.common.CourseReminderControl
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.schedule.data.schedule.CurrentWeekResolver
import java.time.LocalDateTime
import java.time.ZoneId

/** Business planning lives here; Android AlarmManager/notification delivery stays in the host bridge. */
internal class ScheduleReminderController : CourseReminderControl {
    private val services get() = PluginScope.services
    private val context get() = PluginScope.environment.context
    override fun reschedule() {
        val cache = ScheduleCache.current()
        val user = SessionStore.currentUser()
        if (user == null || !cache.remindersEnabled() || !cache.canUseUndergraduateAcademics()) { cancel(); return }
        val resolver = CurrentWeekResolver(cache)
        val term = resolver.getCachedSemesterKey() ?: return
        val config = resolver.resolveLocalConfig()?.config ?: return
        val courses = cache.getSchedule(term.raw).orEmpty()
        val now = LocalDateTime.now()
        val reminders = CourseReminderPlan.build(user.xh, term.raw,
            config.startTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate(), courses, now, config.isInSemester)
            .map { occurrence ->
                val at = occurrence.courseStart.minusMinutes(cache.reminderMinutes().toLong())
                val payload = CourseReminderPayload(occurrence.course.name.orEmpty(), occurrence.course.location,
                    com.ahu.ahutong.plugin.schedule.data.schedule.ScheduleSectionTimes.getCourseClockRange(occurrence.course),
                    occurrence.courseStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    occurrence.key.hashCode(), cache.liveCountdownEnabled(), occurrence.key)
                PluginReminder(occurrence.key, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    occurrence.course.name.orEmpty(), "${occurrence.courseStart.toLocalTime()} · ${occurrence.course.location.orEmpty()}", "schedule",
                    extraJson = com.google.gson.Gson().toJson(payload), expiresAtMillis = payload.courseStartAtMillis)
            }.filter { it.triggerAtMillis > System.currentTimeMillis() }.take(80)
        services.resolve(PluginNotificationScheduler::class.java)?.replace("schedule", reminders)
        services.resolve(PluginWorkScheduler::class.java)?.enqueue("schedule", "replan", "course_reminders",
            repeatIntervalMs = 24 * 60 * 60 * 1000L, replace = false)
    }
    override fun cancel() {
        services.resolve(PluginNotificationScheduler::class.java)?.cancel("schedule")
        services.resolve(PluginWorkScheduler::class.java)?.cancelAll("schedule")
    }
    override fun cancelActiveReminder() = cancel()
    override fun openSystemSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.applicationContext.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun canScheduleExactReminders(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.applicationContext.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
