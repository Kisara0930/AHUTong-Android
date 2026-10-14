package com.ahu.ahutong.plugin.bathroom

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.network.retrofit
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.data.session.CampusSessionService
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface BathroomApi {
    @GET("/charge/feeitem/toAppitem")
    suspend fun enterFeeItem(@Query("feeitemid") feeitemid: String, @Query("appId") appId: String,
        @Query("synjones-auth") token: String, @Query("visitor") visitor: String = "0",
        @Query("type") type: String = "app"): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/")
    @GET("/charge/feeitem/singleFeeitem")
    suspend fun getSingleFeeItem(@Query("feeitemid") feeitemid: String): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/", "Origin: https://ycard.ahu.edu.cn")
    @POST("/charge/feeitem/getThirdData")
    suspend fun getFeeItemThirdData(@Body body: okhttp3.RequestBody): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/", "Origin: https://ycard.ahu.edu.cn")
    @POST("/blade-pay/pay")
    suspend fun pay(@Body body: okhttp3.RequestBody): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/")
    @GET("/charge/pay/getpayinfo")
    suspend fun getPayInfo(@Query("orderid") orderId: String,
        @Query("userAgent") userAgent: String = "wechat-mp"): Response<ResponseBody>
    @Headers("Referer: https://ycard.ahu.edu.cn/charge-app/")
    @GET("/charge/order/getCurrentTime")
    suspend fun getCurrentTime(): Response<ResponseBody>
}

class BathroomGateway(private val session: CampusSessionService) {
    private val generation = AccountGeneration.current
    private val client = session.client(CampusRealm.CAMPUS_CARD).newBuilder()
        .retryOnConnectionFailure(false).apply { interceptors().add(0, noAutomaticFormReplay) }
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
                .header("X-Requested-With", "com.tencent.mm")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Version/4.0 Chrome/150.0.0.0 Mobile Safari/537.36 " +
                    "XWEB/1500117 MMWEBSDK/20260502 MicroMessenger/8.0.76 WeChat/arm64 " +
                    "Weixin NetType/WIFI Language/zh_CN ABI/arm64")
            if (request.url.encodedPath != "/charge/feeitem/toAppitem") {
                builder.header("Authorization", "Basic Y2hhcmdlOmNoYXJnZV9zZWNyZXQ=")
            }
            chain.proceed(builder.build())
        }.build()
    private val api = retrofit("https://ycard.ahu.edu.cn/", client).create(BathroomApi::class.java)

    private suspend fun request(call: suspend BathroomApi.() -> Response<ResponseBody>): Response<ResponseBody> {
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

    suspend fun getBathroomInfo(bathroom: String, tel: String): AhuResult<BathroomTelInfo> = try {
        val (fee, app) = when (bathroom) {
            "竹园/龙河" -> "409" to "55"
            "桔园/蕙园" -> "430" to "56"
            else -> error("目前没有这个浴室")
        }
        consume(request { enterFeeItem(fee, app, requireNotNull(session.campusCardToken())) })
        consume(request { getSingleFeeItem(fee) })
        consume(request { getFeeItemThirdData(FormBody.Builder().add("feeitemid", fee)
            .add("type", "select").add("level", "0").build()) })
        val json = consume(request { getFeeItemThirdData(FormBody.Builder().add("feeitemid", fee)
            .add("type", "IEC").add("level", "1").add("telPhone", tel).build()) })
        AhuResult.Success(Gson().fromJson(json, BathroomTelInfo::class.java))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { AhuResult.Failure(AhuError.Unknown(e.message ?: "浴室信息加载失败")) }

    suspend fun pay(body: RequestBody): AhuResult<Response<ResponseBody>> = try {
        AhuResult.Success(request {
            when (body) {
                is BathroomPayInfoRequest -> getPayInfo(body.orderId)
                is BathroomCurrentTimeRequest -> getCurrentTime()
                else -> pay(body.toFormBody())
            }
        })
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { AhuResult.Failure(AhuError.Unknown(e.message ?: "浴室支付请求失败")) }

    private fun consume(response: Response<ResponseBody>): String {
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            error("浴室缴费会话初始化失败 (${response.code()})")
        }
        return requireNotNull(response.body()).use { it.string() }
    }
}
