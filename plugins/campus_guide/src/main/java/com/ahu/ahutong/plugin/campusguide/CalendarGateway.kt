package com.ahu.ahutong.plugin.campusguide

import com.ahu.ahutong.core.plugin.PluginHostServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class SchoolCalendarYearsResponse(val years: List<String>, val latestYear: String?)

/** Public calendar service is owned by this plugin and needs no campus session. */
class CalendarGateway(private val host: PluginHostServices) {
    suspend fun getSchoolCalendarYears(): SchoolCalendarYearsResponse = withContext(Dispatchers.IO) {
        host.http().newCall(Request.Builder().url("https://openahu.org/api/school_calendars").build())
            .execute().use { response ->
                check(response.isSuccessful) { "校历目录加载失败 (${response.code})" }
                val json = JSONObject(requireNotNull(response.body).string())
                val array = json.optJSONArray("years")
                val years = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
                SchoolCalendarYearsResponse(years, json.optString("latestYear").takeIf { it.isNotBlank() })
            }
    }

    suspend fun getSchoolCalendar(year: String? = null): Response = withContext(Dispatchers.IO) {
        require(year == null || SchoolCalendarYearPolicy.normalize(listOf(year)).isNotEmpty())
        val path = if (year == null) "/download/xiaoli.jpg" else "/api/school_calendars/$year"
        host.http().newCall(Request.Builder().url("https://openahu.org$path").build()).execute()
    }
}
