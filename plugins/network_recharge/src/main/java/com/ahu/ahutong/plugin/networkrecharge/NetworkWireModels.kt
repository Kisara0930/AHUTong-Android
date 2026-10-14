package com.ahu.ahutong.plugin.networkrecharge

import com.google.gson.annotations.SerializedName

data class NetworkFeeItemPageResponse(
    @SerializedName("msg") val msg: String?,
    @SerializedName("code") val code: Int,
    @SerializedName("view") val view: String?,
    @SerializedName("feeitem") val feeItem: NetworkFeeItem?
)

data class NetworkFeeInfoResponse(
    @SerializedName("msg") val msg: String?,
    @SerializedName("code") val code: Int,
    @SerializedName("map") val map: NetworkFeeInfoMap?
)

data class NetworkFeeInfoMap(
    @SerializedName("showData") val showData: Map<String, String>?,
    @SerializedName("data") val data: NetworkThirdPartyData?
)

data class NetworkOrderResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: NetworkOrderData?,
    @SerializedName("msg") val msg: String?
)

data class NetworkOrderData(@SerializedName("orderid") val orderId: String)

data class NetworkAccountPayInfoResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: NetworkAccountPayInfoData?,
    @SerializedName("msg") val msg: String?
)

data class NetworkAccountPayInfoData(
    @SerializedName("passwordMap") val passwordMap: Map<String, String>?
)

data class NetworkFinalPayResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: String?,
    @SerializedName("msg") val msg: String?
)

data class NetworkThirdDataResponse(
    @SerializedName("msg") val msg: String?,
    @SerializedName("code") val code: Int
)
