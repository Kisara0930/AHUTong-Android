package com.ahu.ahutong.plugin.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Original course reminder presentation, hosted through the stable notification bridge. */
internal object CourseLiveUpdateHelper {
    private const val CHANNEL = "course_reminder"
    fun build(context: Context, payload: CourseReminderPayload, holidayNotice: String?): Notification? {
        val startAt = payload.courseStartAtMillis
        if (startAt != null && startAt <= System.currentTimeMillis()) return null
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "课前提醒", NotificationManager.IMPORTANCE_HIGH))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra("plugin_id", "schedule")?.putExtra("plugin_route", "schedule")
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = launch?.let { PendingIntent.getActivity(context, payload.notificationId, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val live = Build.VERSION.SDK_INT >= 36 && payload.supportsLiveCountdown &&
            ScheduleCache.current().liveCountdownEnabled() && NotificationManagerCompat.from(context).canPostPromotedNotifications()
        val content = if (live && startAt != null) {
            val startText = Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) + " 开始上课"
            listOfNotNull(payload.location?.takeIf { it.isNotBlank() }, startText).joinToString(" · ")
        } else buildString {
            val remaining = startAt?.minus(System.currentTimeMillis())
            if (remaining != null) append("${((remaining - 1).coerceAtLeast(0) / 60_000 + 1)} 分钟后上课") else append("课前提醒")
            payload.location?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            payload.timeText?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }.let { it }
        val text = if (holidayNotice.isNullOrBlank()) content else "$content\n$holidayNotice"
        fun builder() = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(context.applicationInfo.icon).setContentTitle(payload.courseName).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL).setOnlyAlertOnce(true).setContentIntent(pending)
        if (live && startAt != null) {
            val notification = builder().setOngoing(true).setAutoCancel(false)
                .setWhen(startAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setTimeoutAfter(startAt - System.currentTimeMillis()).setRequestPromotedOngoing(true)
                .addExtras(Bundle().apply { putString("course_reminder_occurrence", payload.occurrenceKey) }).build()
            if (Build.VERSION.SDK_INT >= 36 && notification.hasPromotableCharacteristics()) return notification
        }
        return builder().setAutoCancel(true).build()
    }
}
