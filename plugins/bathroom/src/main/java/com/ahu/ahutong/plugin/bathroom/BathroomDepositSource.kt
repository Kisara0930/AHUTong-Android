package com.ahu.ahutong.plugin.bathroom

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.bathroom.BathroomTelInfo

/**
 * 浴室缴费的深接口：协议的五步请求、动态密码映射与手机号落盘由适配器完成。
 */
interface BathroomDepositSource {

    /** 查浴室账户（手机号长度与查询时序由调用方把控）。 */
    suspend fun bathroomInfo(bathroom: String, tel: String): AhuResult<BathroomTelInfo>

    suspend fun pay(payment: BathroomPayment): AhuResult<RechargeReceipt>

    /** 上次用过的手机号（预填用，从未用过则为 null）。 */
    fun savedPhone(): String?
}
