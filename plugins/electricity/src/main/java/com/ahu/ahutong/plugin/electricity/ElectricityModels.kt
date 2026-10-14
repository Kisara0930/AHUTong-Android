package com.ahu.ahutong.plugin.electricity
import com.ahu.ahutong.data.model.Data as BathroomAccount
import com.ahu.ahutong.data.model.ElectricityController
import com.ahu.ahutong.data.model.RoomSelectionInfo
import com.google.gson.annotations.SerializedName

data class RechargeReceipt(val reference: String)

enum class ElectricityOptionLevel {
    Initial,
    Buildings,
    Floors,
    Rooms
}

data class ElectricityOptionQuery(
    val controller: ElectricityController,
    val selection: RoomSelectionInfo,
    val level: ElectricityOptionLevel
)

data class ElectricityRoom(
    val displayInfo: String?,
    val details: ElectricityRoomDetails
)

data class ElectricityRoomDetails(
    @SerializedName("area") val area: String?,
    @SerializedName("buildingName") val buildingName: String?,
    @SerializedName("areaName") val areaName: String?,
    @SerializedName("floorName") val floorName: String?,
    @SerializedName("floor") val floor: String?,
    @SerializedName("aid") val aid: String?,
    @SerializedName("account") val account: String?,
    @SerializedName("building") val building: String?,
    @SerializedName("room") val room: String?,
    @SerializedName("roomName") val roomName: String?
)

data class ElectricityPayment(
    val controller: ElectricityController,
    val amount: String,
    val password: String,
    val room: ElectricityRoomDetails
)
