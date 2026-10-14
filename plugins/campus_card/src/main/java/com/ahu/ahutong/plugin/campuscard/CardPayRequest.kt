package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.data.model.CardRechargeBank

class CardPayRequest(orderId: String, bank: CardRechargeBank) : RequestBody() {

    init {
        val payStep = "2"
        val (payType, payTypeId) = when (bank) {
            CardRechargeBank.AGRICULTURAL_BANK -> "BANKCARD" to "63"
            CardRechargeBank.CHINA_MERCHANTS_BANK -> "PAYMENTCASHIER" to "81"
            CardRechargeBank.ALIPAY -> error("Alipay recharge is handled outside the campus-card API")
        }
        val redirectUrl = "https://ycard.ahu.edu.cn/payment/?name=result"
        val userAgent = "h5"
        val synAccessSource = "h5"

        addParams(
            signedPaymentParams(
                linkedMapOf(
                    "opAppId" to "",
                    "paytypeid" to payTypeId,
                    "paytype" to payType,
                    "paystep" to payStep,
                    "orderid" to orderId,
                    "redirect_url" to redirectUrl,
                    "userAgent" to userAgent
                )
            )
        )
        // 该字段由接口接收，但不参与校园卡支付签名（保持既有 wire 契约）。
        addParam("synAccessSource", synAccessSource)
    }

}
