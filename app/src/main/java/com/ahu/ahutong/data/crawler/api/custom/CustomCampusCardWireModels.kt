package com.ahu.ahutong.data.crawler.api.custom

import com.google.gson.annotations.SerializedName

/**
 * 自定义一卡通服务的响应形状。
 *
 * 金额统一为「分」；封套与原有 berserker 平台一致，错误文案散落在
 * message / msg / error_description / error 里，取文案时按该顺序挨个找。
 */

/** 登录 / 刷新 token 的响应（字段可缺省，故全部可空）。 */
data class CustomTokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Long? = null,
    @SerializedName("token_type") val tokenType: String? = null,
    @SerializedName("sno") val sno: String? = null,
    @SerializedName("name") val name: String? = null
)

/** 余额：GET /berserker-app/ykt/tsm/codebarPayinfo。 */
data class CustomBalanceResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("status") val status: Int? = null,
    @SerializedName("data") val data: List<CustomBalanceItem>? = null,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("message") val message: String? = null
)

data class CustomBalanceItem(
    /**
     * 电子账户余额，单位：分。
     * 可空：上游改字段名时不能静默当成 0（对一张能消费的卡，错误的 0 比报错更危险），
     * 由调用方转成协议变更错误。
     */
    @SerializedName("elec_accamt") val elecAccAmt: Long? = null
)
