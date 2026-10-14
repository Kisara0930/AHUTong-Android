package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.network.retrofit
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.data.session.CampusSessionService
import com.ahu.ahutong.sdk.nativebridge.CampusCardNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface CampusCardApi {
    @GET("/berserker-app/ykt/tsm/queryCard")
    suspend fun loadCardRecharge(@Query("scene") scene: String = "cardRecharge",
        @Query("synAccessSource") source: String = "h5"): Response<CardInfo>
    @GET("/berserker-search/search/personal/turnover")
    suspend fun getTurnover(@Query("size") size: Int, @Query("current") current: Int,
        @Query("timeFrom") timeFrom: String?, @Query("timeTo") timeTo: String?, @Query("type") type: Int?,
        @Query("synAccessSource") source: String = "h5"): Response<TurnoverResponse>
    @GET("/berserker-search/statistics/turnover/count")
    suspend fun getTurnoverCount(@Query("timeFrom") timeFrom: String, @Query("timeTo") timeTo: String,
        @Query("synAccessSource") source: String = "h5"): Response<TurnoverCountResponse>
    @POST("/charge/order/thirdOrder")
    suspend fun getOrderThirdData(@Body body: okhttp3.RequestBody): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/", "Origin: https://ycard.ahu.edu.cn")
    @POST("/blade-pay/pay")
    suspend fun pay(@Body body: okhttp3.RequestBody): Response<ResponseBody>
}

class CampusCardGateway(private val session: CampusSessionService) {
    private val generation = AccountGeneration.current
    private val api = retrofit("https://ycard.ahu.edu.cn/", session.client(CampusRealm.CAMPUS_CARD).newBuilder()
        .retryOnConnectionFailure(false).apply { interceptors().add(0, noAutomaticFormReplay) }.build())
        .create(CampusCardApi::class.java)
    private val wisdom = session.client(CampusRealm.WISDOM).newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    private suspend fun <T> request(call: suspend CampusCardApi.() -> Response<T>): Response<T> {
        AccountGeneration.requireCurrent(generation)
        val attemptedToken = session.campusCardToken()
        check(!attemptedToken.isNullOrBlank()) { "校园卡会话未就绪，请先登录" }
        val response = api.call().also { AccountGeneration.requireCurrent(generation) }
        if (response.code() != 401) return response
        val rejectedToken = response.raw().request.header("Synjones-Auth")?.removePrefix("bearer ") ?: attemptedToken
        if (session.refreshCampusCardToken(rejectedToken).isNullOrBlank()) return response
        response.errorBody()?.close()
        AccountGeneration.requireCurrent(generation)
        return api.call().also { AccountGeneration.requireCurrent(generation) }
    }
    private suspend fun <T> result(block: suspend () -> T): AhuResult<T> = try {
        AhuResult.Success(block())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { AhuResult.Failure(AhuError.Unknown(e.message ?: "校园卡请求失败")) }
    private fun <T> body(response: Response<T>): T {
        if (!response.isSuccessful) { response.errorBody()?.close(); error("校园卡服务错误 (${response.code()})") }
        return requireNotNull(response.body()) { "校园卡服务返回空数据" }
    }

    suspend fun getCardInfo(): AhuResult<CardInfo> = result { body(request { loadCardRecharge() }) }
    suspend fun getOrderThirdData(value: RequestBody): AhuResult<Response<ResponseBody>> = result {
        request { getOrderThirdData(value.toFormBody()) }
    }
    suspend fun pay(value: RequestBody): AhuResult<Response<ResponseBody>> = result { request { pay(value.toFormBody()) } }
    suspend fun getBillPage(page: Int, size: Int, timeFrom: String? = null, timeTo: String? = null,
        type: Int? = null): AhuResult<TurnoverPage> = result {
        requireNotNull(body(request { getTurnover(size, page, timeFrom, timeTo, type) }).data)
    }
    suspend fun getBillSummary(timeFrom: String, timeTo: String): AhuResult<TurnoverCount> = result {
        requireNotNull(body(request { getTurnoverCount(timeFrom, timeTo) }).data)
    }

    private suspend fun wisdomJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        fun call(): JSONObject {
            AccountGeneration.requireCurrent(generation)
            return wisdom.newCall(Request.Builder().url("https://adwmh.ahu.edu.cn$path").build()).execute().use {
                check(it.isSuccessful) { "校园卡请求失败 (${it.code})" }
                AccountGeneration.requireCurrent(generation)
                JSONObject(requireNotNull(it.body).string())
            }
        }
        val initial = call()
        if (initial.optInt("code") == 10000) initial else {
            check(session.refresh(CampusRealm.WISDOM)) { "校园账号会话已失效" }
            call()
        }.also { check(it.optInt("code") == 10000) { "校园卡接口拒绝请求" } }
    }

    suspend fun getBalance(): Double = withContext(Dispatchers.IO) {
        try { wisdomJson("/xzxcard/yue").getDouble("object") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            try {
                CampusCardNative.ensureLoaded()
                val raw = CampusCardNative.getBalance(session.nativeCookies(CampusRealm.WISDOM, generation))
                AccountGeneration.requireCurrent(generation)
                JSONObject(session.acceptNativeResult(CampusRealm.WISDOM, raw, generation)).getDouble("object")
            } catch (e: LinkageError) { throw IllegalStateException("校园卡原生组件暂不可用", e) }
        }
    }

    suspend fun getQrcode(): Result<String> = withContext(Dispatchers.IO) {
        try { Result.success(wisdomJson("/xzxcard/qrcode").getString("object").also { check(it.isNotBlank()) }) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            try {
                CampusCardNative.ensureLoaded()
                val raw = CampusCardNative.getQrcode(session.nativeCookies(CampusRealm.WISDOM, generation))
                AccountGeneration.requireCurrent(generation)
                val value = JSONObject(session.acceptNativeResult(CampusRealm.WISDOM, raw, generation)).getString("object")
                check(value.isNotBlank())
                Result.success(value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            catch (e: LinkageError) { Result.failure(e) }
        }
    }
}
