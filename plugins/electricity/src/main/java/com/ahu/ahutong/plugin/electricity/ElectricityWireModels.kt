package com.ahu.ahutong.plugin.electricity

import com.ahu.ahutong.data.model.CampusDataItem
import com.google.gson.annotations.SerializedName

data class ElectricityCampusApiResponse(
    @SerializedName("msg") val msg: String?,
    @SerializedName("code") val code: Int,
    @SerializedName("map") val map: ElectricityCampusMap?
)

data class ElectricityCampusMap(
    @SerializedName("data") val data: List<CampusDataItem>?
)

data class ElectricityRoomInfoApiResponse(
    @SerializedName("msg") val msg: String?,
    @SerializedName("code") val code: Int,
    @SerializedName("map") val map: ElectricityRoomInfoMap?
)

data class ElectricityRoomInfoMap(
    @SerializedName("showData") val showData: ElectricityRoomShowData?,
    @SerializedName("data") val data: ElectricityRoomDetails?
)

data class ElectricityRoomShowData(
    @SerializedName("信息") val info: String?
)

data class ElectricityPaymentData(
    @SerializedName("area") val area: String,
    @SerializedName("buildingName") val buildingName: String,
    @SerializedName("areaName") val areaName: String,
    @SerializedName("extdata") val extraData: String = "",
    @SerializedName("floorName") val floorName: String,
    @SerializedName("floor") val floor: String,
    @SerializedName("aid") val aid: String,
    @SerializedName("account") val account: String,
    @SerializedName("building") val building: String,
    @SerializedName("room") val room: String,
    @SerializedName("roomName") val roomName: String,
    @SerializedName("myCustomInfo") val customInfo: String
)

data class ElectricityOrderResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: ElectricityOrderData?,
    @SerializedName("msg") val msg: String
)

data class ElectricityOrderData(@SerializedName("orderid") val orderId: String)

data class ElectricityAccountPayInfoResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: ElectricityAccountPayInfoData?,
    @SerializedName("msg") val msg: String
)

data class ElectricityAccountPayInfoData(
    @SerializedName("passwordMap") val passwordMap: Map<String, String>?
)

data class ElectricityFinalPayResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: String?,
    @SerializedName("msg") val msg: String?
)
