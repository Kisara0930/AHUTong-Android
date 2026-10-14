package com.ahu.ahutong.plugin.schedule

import com.ahu.ahutong.data.network.retrofit
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.plugin.schedule.data.crawler.model.jwxt.CourseTable
import com.ahu.ahutong.plugin.schedule.data.crawler.model.jwxt.CurrentTeachWeek
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ScheduleApi {
    @GET("/student/for-std/course-table") suspend fun fetchCourseTableBasicInfo(): Response<ResponseBody>
    @GET("/student/for-std/course-table/semester/{id}/print-data")
    suspend fun getCourse(@Path("id") semesterPathId: Int, @Query("semesterId") semesterQueryId: Int,
        @Query("hasExperiment") hasExperiment: Boolean = false): CourseTable
    @GET("/student/home/get-current-teach-week") suspend fun getCurrentTeachWeek(): CurrentTeachWeek
    companion object {
        fun current(): ScheduleApi {
            PluginScope.requireUndergraduate()
            return retrofit("https://jw.ahu.edu.cn/", PluginScope.session.client(CampusRealm.ACADEMIC)).create(ScheduleApi::class.java)
        }
    }
}
