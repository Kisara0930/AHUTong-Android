package com.ahu.ahutong.plugin.free_classroom

import com.ahu.ahutong.data.network.retrofit
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.plugin.free_classroom.data.crawler.model.jwxt.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

private interface ClassroomApi {
    @GET("/student/ws/room/get-buildings")
    suspend fun buildings(@Query("campusId") campusId: Int, @Query("hasDataPermission") permission: Boolean = false): GetBuildingsResponse
    @POST("/student/ws/room-borrow/free-list")
    suspend fun freeRooms(@Body request: GetFreeRoomsRequest): GetFreeRoomsResponse
}

internal object FreeClassroomGateway {
    private fun api(): ClassroomApi {
        PluginScope.requireUndergraduate()
        return retrofit("https://jw.ahu.edu.cn/", PluginScope.session.client(CampusRealm.ACADEMIC)).create(ClassroomApi::class.java)
    }
    suspend fun buildings(campusId: Int): GetBuildingsResponse {
        val generation = AccountGeneration.current
        return api().buildings(campusId).also { AccountGeneration.requireCurrent(generation) }
    }
    suspend fun freeRooms(request: GetFreeRoomsRequest): GetFreeRoomsResponse {
        val generation = AccountGeneration.current
        return api().freeRooms(request).also { AccountGeneration.requireCurrent(generation) }
    }
}
