package com.ahu.ahutong.plugin.networkrecharge

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSnapshot
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSource
import com.ahu.ahutong.plugin.networkrecharge.RechargeReceipt

class FakeNetworkRechargeSource(
    var loadResult: AhuResult<NetworkRechargeSnapshot>,
    var payResult: AhuResult<RechargeReceipt>
) : NetworkRechargeSource {

    var loadCalls = 0
        private set

    data class Payment(val snapshot: NetworkRechargeSnapshot, val amount: String, val password: String)
    val payments = mutableListOf<Payment>()

    override suspend fun load(): AhuResult<NetworkRechargeSnapshot> {
        loadCalls++
        return loadResult
    }

    override suspend fun pay(
        snapshot: NetworkRechargeSnapshot,
        amount: String,
        password: String
    ): AhuResult<RechargeReceipt> {
        payments += Payment(snapshot, amount, password)
        return payResult
    }
}
