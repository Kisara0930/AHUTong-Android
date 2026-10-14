package com.ahu.ahutong.plugin.networkrecharge

import com.google.gson.annotations.SerializedName

/** A successful recharge reference shown by the feature. */
data class RechargeReceipt(val reference: String)

data class NetworkFeeItem(
    @SerializedName("name") val name: String?,
    @SerializedName("layout") val layout: String?,
    @SerializedName("maxmoney") val maxMoney: String?,
    @SerializedName("daymaxmoney") val dayMaxMoney: String?,
    @SerializedName("billing_unit") val billingUnit: String?
)

/** The account payload required again when a network recharge is submitted. */
data class NetworkThirdPartyData(
    @SerializedName("state_time") val stateTime: String?,
    @SerializedName("state_memo") val stateMemo: String?,
    @SerializedName("balance") val balance: String?,
    @SerializedName("use_time") val useTime: String?,
    @SerializedName("tsmAbstract") val tsmAbstract: String?,
    @SerializedName("use_money") val useMoney: String?,
    @SerializedName("use_flow") val useFlow: String?,
    @SerializedName("account") val account: String?,
    @SerializedName("user_state") val userState: String?,
    @SerializedName("start_date") val startDate: String?
)

data class NetworkRechargeSnapshot(
    val feeItem: NetworkFeeItem,
    val showData: Map<String, String>,
    val paymentData: NetworkThirdPartyData
)
