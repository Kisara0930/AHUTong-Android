package com.ahu.ahutong.plugin.schedule.widget
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.ahu.ahutong.data.debug.DebugClock
import com.ahu.ahutong.plugin.schedule.data.schedule.*
import java.text.SimpleDateFormat
import java.util.Locale

internal object ScheduleWidgetRenderer {
    fun render(context: Context, appWidgetId: Int, options: android.os.Bundle,
        snapshot: WidgetScheduleSnapshot, holiday: ScheduleHoliday?): RemoteViews {
        val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val isLarge = minWidth >= 300
        val layoutRes = if (isLarge) {
            hostResource(context, "layout", "layout_adaptive_test_large")
        } else {
            hostResource(context, "layout", "layout_adaptive_test_small")
        }
        val titleId = if (isLarge) {
            hostResource(context, "id", "adaptive_test_title_large")
        } else {
            hostResource(context, "id", "adaptive_test_title_small")
        }
        val subtitleId = if (isLarge) {
            hostResource(context, "id", "adaptive_test_subtitle_large")
        } else {
            hostResource(context, "id", "adaptive_test_subtitle_small")
        }
        val itemsContainerId = if (isLarge) {
            hostResource(context, "id", "adaptive_test_items_large")
        } else {
            hostResource(context, "id", "adaptive_test_items_small")
        }
        // 教务课表仍然只读缓存；公开日历在 IO 协程中查询，不触发教务登录。
        val currentMinutes = DebugClock.currentMinutes()
        val todayCourses = todayWidgetCourses(snapshot.courses, snapshot.config)
        val remainingCourses = todayCourses.filter {
            currentMinutes <= ScheduleSectionTimes.getCourseTimeRangeInMinutes(it).last
        }

        val displayCourses = remainingCourses
        val widgetColors = resolve(context)
        val titleText: String
        val subtitleText: String
        if (snapshot.unavailableMessage != null) {
            titleText = "课表待更新"
            subtitleText = SimpleDateFormat("MM-dd/EE", Locale.CHINA).format(DebugClock.nowDate())
        } else if (displayCourses.isEmpty()) {
            titleText = "没课啦🎉"
            subtitleText = SimpleDateFormat("MM-dd/EE", Locale.CHINA).format(DebugClock.nowDate())
        } else {
            titleText = "还剩 ${remainingCourses.size} 节"
            subtitleText = SimpleDateFormat("MM-dd/EE", Locale.CHINA).format(DebugClock.nowDate())
        }
        // 断网会撤销 StateFlow 并请求重绘；旧的并发渲染也不能重新写回已撤销的标记。
        val currentHoliday = holiday
        val remoteViews = RemoteViews(context.packageName, layoutRes)
        remoteViews.setOnClickPendingIntent(
            hostResource(context, "id", "layout_wight"),
            createRefreshPendingIntent(context, appWidgetId)
        )
        remoteViews.setTextViewText(titleId, titleText)
        remoteViews.setTextViewText(
            subtitleId,
            currentHoliday?.let { "$subtitleText · ${scheduleHolidayLabel(it)}" } ?: subtitleText
        )
        remoteViews.setTextViewText(
            hostResource(context, "id", "widget_last_fetched_at"),
            listOfNotNull(widgetScheduleFetchedText(snapshot.fetchedAt), snapshot.notice).joinToString(" · ") +
                (currentHoliday?.let { "\n${scheduleHolidayNotice(it)}" } ?: "")
        )
        remoteViews.setTextColor(titleId, widgetColors.primaryText.toArgb())
        remoteViews.setTextColor(subtitleId, widgetColors.secondaryText.toArgb())
        remoteViews.setTextColor(hostResource(context, "id", "widget_last_fetched_at"), widgetColors.secondaryText.toArgb())
        remoteViews.removeAllViews(itemsContainerId)
        setRemoteViewBackgroundColor(
            remoteViews,
            hostResource(context, "id", "layout_wight"),
            widgetColors.background.toArgb()
        )
        if (displayCourses.isEmpty()) {
            val emptyItem = RemoteViews(context.packageName, hostResource(context, "layout", "layout_widget_item"))
            emptyItem.setViewVisibility(hostResource(context, "id", "little_circle"), View.GONE)
            emptyItem.setTextViewText(hostResource(context, "id", "course_name_tv"), snapshot.unavailableMessage ?: "暂无课程")
            emptyItem.setViewVisibility(hostResource(context, "id", "course_time_tv"), View.GONE)
            emptyItem.setTextViewText(hostResource(context, "id", "course_location_tv"), "点击打开安大通查看完整课表")
            emptyItem.setOnClickPendingIntent(hostResource(context, "id", "widget_item_color_bg"),
                PendingIntent.getActivity(context, appWidgetId, launchIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            emptyItem.setTextColor(hostResource(context, "id", "little_circle"), widgetColors.off.toArgb())
            emptyItem.setTextColor(hostResource(context, "id", "course_name_tv"), widgetColors.primaryText.toArgb())
            emptyItem.setTextColor(hostResource(context, "id", "course_time_tv"), widgetColors.secondaryText.toArgb())
            emptyItem.setTextColor(hostResource(context, "id", "course_location_tv"), widgetColors.secondaryText.toArgb())
            setRemoteViewBackgroundColor(
                emptyItem,
                hostResource(context, "id", "widget_item_color_bg"),
                Color.TRANSPARENT
            )
            remoteViews.addView(itemsContainerId, emptyItem)
        } else {
            displayCourses.forEach {
                val currentRange = ScheduleSectionTimes.getCourseTimeRangeInMinutes(it)
                val isPassed = currentMinutes > currentRange.first
                val isOngoing = currentMinutes in currentRange
                val item = RemoteViews(context.packageName, hostResource(context, "layout", "layout_widget_item"))
                item.setTextViewText(hostResource(context, "id", "little_circle"), if (isPassed) "●" else "○")
                item.setTextViewText(hostResource(context, "id", "course_name_tv"), it.name)
                item.setTextViewText(
                    hostResource(context, "id", "course_time_tv"),
                    "${it.startTime}-${it.startTime + it.length - 1}节"
                )
                item.setTextViewText(hostResource(context, "id", "course_location_tv"), shortenLocation(it.location))
                item.setTextColor(
                    hostResource(context, "id", "little_circle"),
                    if (isPassed) widgetColors.on.toArgb() else widgetColors.off.toArgb()
                )
                item.setTextColor(
                    hostResource(context, "id", "course_name_tv"),
                    if (isOngoing) widgetColors.ongoingPrimaryText.toArgb() else widgetColors.primaryText.toArgb()
                )
                item.setTextColor(
                    hostResource(context, "id", "course_time_tv"),
                    if (isOngoing) widgetColors.ongoingSecondaryText.toArgb() else widgetColors.secondaryText.toArgb()
                )
                item.setTextColor(
                    hostResource(context, "id", "course_location_tv"),
                    if (isOngoing) widgetColors.ongoingSecondaryText.toArgb() else widgetColors.secondaryText.toArgb()
                )

                setRemoteViewBackgroundColor(
                    item,
                    hostResource(context, "id", "widget_item_color_bg"),
                    if (isOngoing) widgetColors.activatedRow.toArgb() else Color.TRANSPARENT
                )
                remoteViews.addView(itemsContainerId, item)
            }
        }
        return remoteViews
    }

    private fun setRemoteViewBackgroundColor(
        remoteViews: RemoteViews,
        viewId: Int,
        color: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            remoteViews.setColorStateList(
                viewId,
                "setBackgroundTintList",
                ColorStateList.valueOf(color)
            )
        } else {
            remoteViews.setInt(viewId, "setBackgroundColor", color)
        }
    }

    private fun createRefreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent().setClassName(context.packageName,"com.ahu.ahutong.appwidget.ScheduleAdaptiveWidgetProvider")
            .setAction("com.ahu.ahutong.appwidget.ACTION_REFRESH_SCHEDULE")
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,appWidgetId)
        return PendingIntent.getBroadcast(context,appWidgetId,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun launchIntent(context: Context): Intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        .putExtra("plugin_id","schedule").putExtra("plugin_route","schedule")
    private fun hostResource(context: Context, type: String, name: String): Int = context.resources.getIdentifier(name,type,context.packageName)
        .also { require(it != 0) { "宿主缺少课表组件资源：$type/$name" } }
}
private val LOCATION_SHORTEN_MAP = mapOf(
    "博学北楼" to "博北",
    "博学南楼" to "博南",
    "笃行北楼" to "笃北",
    "笃行南楼" to "笃南",
    "互联大楼" to "互楼",
    "体育场" to "体"
)

private val LOCATION_PATTERN =
    Regex(LOCATION_SHORTEN_MAP.keys.joinToString("|") { Regex.escape(it) })

private fun shortenLocation(location: String?): String =
    (location ?: "").replace(LOCATION_PATTERN) { LOCATION_SHORTEN_MAP[it.value].orEmpty() }
