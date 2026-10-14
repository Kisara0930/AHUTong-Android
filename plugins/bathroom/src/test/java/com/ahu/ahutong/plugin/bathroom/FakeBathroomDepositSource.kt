package com.ahu.ahutong.plugin.bathroom

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.bathroom.BathroomTelInfo
import com.ahu.ahutong.plugin.bathroom.BathroomDepositSource
import com.ahu.ahutong.plugin.bathroom.BathroomPayment
import com.ahu.ahutong.plugin.bathroom.RechargeReceipt

/**
 * [BathroomDepositSource] 的 fake：feature 只看见查询和一次完整支付。
 */
class FakeBathroomDepositSource : BathroomDepositSource {

    var infoHook: (suspend (String, String) -> AhuResult<BathroomTelInfo>)? = null

    var infoCalls = 0
        private set

    var paymentHook: (suspend (BathroomPayment) -> AhuResult<RechargeReceipt>)? = null

    val payments = mutableListOf<BathroomPayment>()

    var savedPhoneValue: String? = null

    override suspend fun bathroomInfo(bathroom: String, tel: String): AhuResult<BathroomTelInfo> {
        infoCalls++
        return requireNotNull(infoHook) { "infoHook 未设置" }.invoke(bathroom, tel)
    }

    override suspend fun pay(payment: BathroomPayment): AhuResult<RechargeReceipt> {
        payments += payment
        return requireNotNull(paymentHook) { "paymentHook 未设置" }.invoke(payment)
    }

    override fun savedPhone(): String? = savedPhoneValue
}
