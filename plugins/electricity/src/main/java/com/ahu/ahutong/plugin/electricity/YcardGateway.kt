package com.ahu.ahutong.plugin.electricity

import com.ahu.ahutong.data.network.retrofit
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.data.session.CampusSessionService
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

/** Account owns authentication; this interface owns only the electricity protocol. */
interface YcardApi {
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/")
    @GET("/charge/feeitem/getRechargeRecord")
    suspend fun getElectricityUsageHistory(
        @Query("feeitemid") feeItemId: String, @Query("building") building: String,
        @Query("floor") floor: String, @Query("room") room: String, @Query("campus") campus: String?,
        @Query("startdate") startDate: String, @Query("enddate") endDate: String,
        @Query("page") page: Int, @Query("row") rows: Int,
        @Query("rtype") recordType: String = "dayEnergy"
    ): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/")
    @GET("/charge/feeitem/singleFeeitem")
    suspend fun getSingleFeeItem(@Query("feeitemid") feeitemid: String): Response<ResponseBody>
    @POST("/charge/order/thirdOrder")
    suspend fun getOrderThirdData(@Body body: RequestBody): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/", "Origin: https://ycard.ahu.edu.cn")
    @POST("/charge/feeitem/getThirdData")
    suspend fun getFeeItemThirdData(@Body body: RequestBody): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/", "Origin: https://ycard.ahu.edu.cn")
    @POST("/blade-pay/pay")
    suspend fun pay(@Body body: RequestBody): Response<ResponseBody>
}

class YcardGateway(private val session: CampusSessionService) {
    private val generation = AccountGeneration.current
    private val client = session.client(CampusRealm.CAMPUS_CARD).newBuilder()
        .retryOnConnectionFailure(false).apply { interceptors().add(0, noAutomaticFormReplay) }.build()
    val loginRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val api = retrofit("https://ycard.ahu.edu.cn/", client).create(YcardApi::class.java)
    suspend fun token(): String? {
        AccountGeneration.requireCurrent(generation)
        return session.campusCardToken().also { AccountGeneration.requireCurrent(generation) }
    }
    suspend fun <T> authorizedCall(request: suspend YcardApi.() -> Response<T>): Response<T> {
        val attemptedToken = token()
        check(!attemptedToken.isNullOrBlank()) { "校园卡会话未就绪，请先登录" }
        val response = api.request()
        AccountGeneration.requireCurrent(generation)
        if (response.code() != 401) return response
        val rejectedToken = response.raw().request.header("Synjones-Auth")?.removePrefix("bearer ") ?: attemptedToken
        if (session.refreshCampusCardToken(rejectedToken).isNullOrBlank()) return response
        response.errorBody()?.close()
        AccountGeneration.requireCurrent(generation)
        return api.request().also { AccountGeneration.requireCurrent(generation) }
    }
}
