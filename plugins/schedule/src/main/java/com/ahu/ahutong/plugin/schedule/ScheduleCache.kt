package com.ahu.ahutong.plugin.schedule

import com.ahu.ahutong.data.model.AcademicAccountType
import com.ahu.ahutong.data.model.Course
import com.ahu.ahutong.data.session.SessionStore
import com.google.gson.reflect.TypeToken

/** A cache instance is bound to one account and generation, including writes after suspended work. */
class ScheduleCache private constructor(private val data: AccountCache) {
    fun getCurrentUser() = SessionStore.currentUser()
    fun canUseUndergraduateAcademics() = getCurrentUser()?.academicAccountType != AcademicAccountType.POSTGRADUATE
    fun getSchoolTerm(): String? = data.string("school_term")
    fun getSchoolYear(): String? = data.string("school_year")
    fun saveSchoolTerm(value: String) = data.put("school_term", value)
    fun saveSchoolYear(value: String) = data.put("school_year", value)
    fun isShowAllCourse(): Boolean = data.string("show_all")?.toBooleanStrictOrNull() ?: false
    fun setShowAllCourse(value: Boolean) = data.put("show_all", value.toString())
    fun getSchoolTermStartTime(schoolYear: String, schoolTerm: String): String? = data.string("term_start.$schoolYear.$schoolTerm")
    fun saveSchoolTermStartTime(schoolYear: String, schoolTerm: String, startTime: String) = data.put("term_start.$schoolYear.$schoolTerm", startTime)
    fun getSchoolTermInSemester(schoolYear: String, schoolTerm: String): Boolean? = data.string("term_in.$schoolYear.$schoolTerm")?.toBooleanStrictOrNull()
    fun getSchoolTermInSemesterObservedOn(schoolYear: String, schoolTerm: String): String? = data.string("term_observed.$schoolYear.$schoolTerm")
    fun saveSchoolTermInSemester(schoolYear: String, schoolTerm: String, isInSemester: Boolean, observedOn: String) {
        data.put("term_in.$schoolYear.$schoolTerm", isInSemester.toString())
        data.put("term_observed.$schoolYear.$schoolTerm", observedOn)
    }
    fun getSchedule(term: String): List<Course>? = data.read("schedule.$term", object : TypeToken<List<Course>>() {}.type)
    fun saveSchedule(term: String, courses: List<Course>) {
        data.save("schedule.$term", courses)
        updateWidgets()
    }
    fun getScheduleFetchedAt(term: String): Long? = data.string("schedule_at.$term")?.toLongOrNull()
    fun saveScheduleFetchedAt(term: String, value: Long) = data.put("schedule_at.$term", value.toString())
    fun getNextSchedule(): List<Course>? = data.read("next_schedule", object : TypeToken<List<Course>>() {}.type)
    fun saveNextSchedule(courses: List<Course>) = data.save("next_schedule", courses)
    fun getPostgraduateWeekStart(term: String): String? = data.string("postgraduate_week.$term")
    fun savePostgraduateWeekStart(term: String, date: String) {
        data.put("postgraduate_week.$term", date)
        updateWidgets()
    }
    fun getGmisScheduleCache(account: String, key: String): String? = forAccount(account).data.string("gmis.$key")
    fun saveGmisScheduleCache(account: String, key: String, value: String) {
        forAccount(account).data.put("gmis.$key", value)
        updateWidgets()
    }
    fun readWidgetKey(key: String): String? = when {
        key.startsWith("gmis.schedule.v1.") -> data.string("gmis." + key.removePrefix("gmis.schedule.v1."))
        key.endsWith(".first_week_monday") -> data.string("postgraduate_week." + key.removePrefix("gmis.").removeSuffix(".first_week_monday"))
        else -> data.string(key)
    }
    fun writeWidgetKey(key: String, value: String) = data.put(key, value)
    fun remindersEnabled() = data.string("reminder_enabled")?.toBooleanStrictOrNull() ?: false
    fun setRemindersEnabled(value: Boolean) = data.put("reminder_enabled", value.toString())
    fun liveCountdownEnabled() = data.string("live_countdown")?.toBooleanStrictOrNull() ?: false
    fun setLiveCountdownEnabled(value: Boolean) = data.put("live_countdown", value.toString())
    fun reminderSettingsMigrated() = data.string("reminder_settings_migrated") == "1"
    fun inheritReminderSettings(enabled: Boolean, live: Boolean) {
        if (data.string("reminder_enabled") == null) setRemindersEnabled(enabled)
        if (data.string("live_countdown") == null) setLiveCountdownEnabled(live)
        data.put("reminder_settings_migrated", "1")
    }
    fun reminderMinutes() = data.string("reminder_minutes")?.toIntOrNull()?.coerceIn(0, 120) ?: 10
    fun setReminderMinutes(value: Int) = data.put("reminder_minutes", value.coerceIn(0,120).toString())
    private fun updateWidgets() {
        PluginScope.services.resolve(com.ahu.ahutong.core.plugin.PluginWidgetService::class.java)?.update("schedule")
    }
    companion object {
        fun current() = ScheduleCache(PluginScope.accountCache())
        fun forAccount(account: String) = ScheduleCache(AccountCache(PluginScope.environment.context, account))
    }
}
