package com.ahu.ahutong.plugin.campuscard

class CardBalanceRequest(
    tranamt: String,
    yktcard: String
) : RequestBody() {

    init {
        val appId = "56321"
        val feeitemid = "401"
        val source = "app"
        val synAccessSource = "h5"

        addParams(
            signedPaymentParams(
                linkedMapOf(
                    "feeitemid" to feeitemid,
                    "appid" to appId,
                    "tranamt" to tranamt,
                    "source" to source,
                    "yktcard" to yktcard,
                    "synAccessSource" to synAccessSource
                )
            )
        )
    }

}
