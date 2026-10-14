package com.ahu.ahutong.plugin.bathroom

data class BathroomOrderResponse(
    val code: Int,
    val success: Boolean,
    val data: BathroomOrderData?,
    val msg: String
)

data class BathroomOrderData(
    val orderid: String?
)

data class BathroomPayPrepareResponse(
    val code: Int,
    val success: Boolean,
    val data: BathroomPayPrepareData?,
    val msg: String
)

data class BathroomPayPrepareData(
    val passwordMap: Map<String, String>?
)
