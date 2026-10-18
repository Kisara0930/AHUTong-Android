package com.ahu.ahutong.data.crawler.api.custom

import com.ahu.ahutong.BuildConfig
import com.ahu.ahutong.data.crawler.model.ycard.TurnoverCountResponse
import com.ahu.ahutong.data.crawler.model.ycard.TurnoverResponse
import com.ahu.ahutong.data.network.AhuHttp
import com.ahu.ahutong.data.network.NetworkLogging
import com.ahu.ahutong.data.network.retrofit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 用户自定义一卡通服务的协议客户端（可选数据源）。
 *
 * 与原有 [com.ahu.ahutong.data.crawler.api.ycard.YcardApi] 是两套完全独立的鉴权：
 * - 原有：CAS 跳转换 ticket，再换 token（logintype=sso）；
 * - 自定义：一卡通账号 + 密码，OAuth2 密码模式（logintype=sno），会话保存在
 *   [CustomCampusCardSession]，不碰 [com.ahu.ahutong.data.crawler.manager.TokenManager]。
 *
 * 头名大小写是本服务最容易踩的坑：平台前置按大小写敏感地取 `User-Agent`，取不到就整条请求 500。
 * OkHttp 在明文 HTTP/1.1 下保留头名原样大小写，因此这里显式写 `User-Agent` 和全小写的
 * `synjones-auth` 即可（Dart 的 HttpClient 会自动小写头名，才会踩坑）。
 */
interface CustomCampusCardApi {

    @FormUrlEncoded
    @POST("/berserker-auth/oauth/token")
    fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("grant_type") grantType: String = "password",
        @Field("scope") scope: String = "all",
        @Field("loginFrom") loginFrom: String = "h5",
        @Field("logintype") logintype: String = "sno",
        @Field("device_token") deviceToken: String = "h5",
        @Field("synAccessSource") synAccessSource: String = "h5",
        @Header("Authorization") authorization: String = BASIC
    ): Call<CustomTokenResponse>

    @FormUrlEncoded
    @POST("/berserker-auth/oauth/token")
    fun refresh(
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
        @Field("scope") scope: String = "all",
        @Field("loginFrom") loginFrom: String = "h5",
        @Field("synAccessSource") synAccessSource: String = "h5",
        @Header("Authorization") authorization: String = BASIC
    ): Call<CustomTokenResponse>

    /** 余额：取 `data[0].elec_accamt`，单位分。 */
    @GET("/berserker-app/ykt/tsm/codebarPayinfo")
    suspend fun getBalance(): Response<CustomBalanceResponse>

    /** 账单流水：结构与原有完全一致，直接复用 [TurnoverResponse]。 */
    @GET("/berserker-search/search/personal/turnover")
    suspend fun getTurnover(
        @Query("size") size: Int,
        @Query("current") current: Int,
        @Query("timeFrom") timeFrom: String? = null,
        @Query("timeTo") timeTo: String? = null,
        @Query("type") type: Int? = null,
        @Query("synAccessSource") synAccessSource: String = "h5"
    ): Response<TurnoverResponse>

    /**
     * 收支汇总（文档未验证，属尽力而为：该校若没有该端点会返回失败，界面不显示汇总）。
     * 结构与原有一致，直接复用 [com.ahu.ahutong.data.crawler.model.ycard.TurnoverCountResponse]。
     */
    @GET("/berserker-search/statistics/turnover/count")
    suspend fun getTurnoverCount(
        @Query("timeFrom") timeFrom: String,
        @Query("timeTo") timeTo: String,
        @Query("synAccessSource") synAccessSource: String = "h5"
    ): Response<TurnoverCountResponse>

    /**
     * 电费级联 / 电费余额。
     * ⚠️ 参数放 query string（不是 form body）；Retrofit 会把 value 里自带的 `&` 编码成 `%26`。
     */
    @POST("/charge/feeitem/getThirdData")
    suspend fun getThirdData(
        @Query("feeitemid") feeItemId: String,
        @Query("type") type: String,
        @Query("level") level: String,
        @Query("campus") campus: String? = null,
        @Query("building") building: String? = null,
        @Query("floor") floor: String? = null,
        @Query("room") room: String? = null
    ): Response<ResponseBody>

    companion object {

        internal const val BASE_URL = "http://ykt.hfue.edu.cn:8020/"

        /** 服务端前置大小写敏感地取 User-Agent，必须显式给出且用规范大小写。 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

        /** 厂商默认客户端凭据，见 app/build.gradle.kts 的 buildConfigField 覆盖说明。 */
        private val BASIC: String = BuildConfig.CAMPUS_CARD_BASIC_AUTH

        private val loggingInterceptor = NetworkLogging.debugInterceptor()

        private val headerInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder().header("User-Agent", USER_AGENT)
            val token = CustomCampusCardSession.getToken()
            if (!token.isNullOrBlank()) {
                // 全小写：平台侧按原样取值，勿改成 Synjones-Auth。
                builder.header("synjones-auth", "bearer $token")
            }
            chain.proceed(builder.build())
        }

        val okHttpClient: OkHttpClient = AhuHttp.plain(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 15,
            writeTimeoutSeconds = 15
        )
            .addInterceptor(headerInterceptor)
            .apply { loggingInterceptor?.let { addInterceptor(it) } }
            .build()

        val API: CustomCampusCardApi =
            retrofit(BASE_URL, okHttpClient).create(CustomCampusCardApi::class.java)

        /**
         * 带鉴权执行一次：无 token 先登录；HTTP 401/403 时清 token 重登并重放一次。
         * 业务码 401/403（HTTP 200）由各调用点解析后再决定是否重试。
         */
        suspend fun <T> authorizedCall(
            request: suspend CustomCampusCardApi.() -> Response<T>
        ): Response<T> {
            val attempted = CustomCampusCardSession.awaitToken()
            if (attempted.isNullOrBlank()) {
                return Response.error(
                    401,
                    (CustomCampusCardSession.lastError ?: "自定义校园卡登录凭证不可用")
                        .toResponseBody("text/plain".toMediaType())
                )
            }
            val first = API.request()
            if (first.code() != 401 && first.code() != 403) return first
            if (CustomCampusCardSession.refreshAfterUnauthorized(attempted).isNullOrBlank()) {
                // 刷新失败：原样返回 first（含未关闭的 errorBody），交给调用方决定如何处理。
                return first
            }
            // 只有在确实要丢弃 first 去重放时，才关闭它的响应体。
            first.errorBody()?.close()
            return API.request()
        }

        internal fun authFailure(code: Int?): Boolean = code == 401 || code == 403
    }
}
