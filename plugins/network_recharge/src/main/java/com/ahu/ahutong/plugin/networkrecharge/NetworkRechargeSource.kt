package com.ahu.ahutong.plugin.networkrecharge

import com.ahu.ahutong.core.common.AhuResult

/**
 * 网费充值的深接口：入口预热、协议解析、签名与三步支付都藏在适配器内。
 */
interface NetworkRechargeSource {

    suspend fun load(): AhuResult<NetworkRechargeSnapshot>

    suspend fun pay(
        snapshot: NetworkRechargeSnapshot,
        amount: String,
        password: String
    ): AhuResult<RechargeReceipt>
}
