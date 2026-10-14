package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.MockDataSignals
import com.ahu.ahutong.plugin.campuscard.CardInfo
import com.ahu.ahutong.plugin.campuscard.PayResponse
import com.ahu.ahutong.data.model.CardRechargeBank

/**
 * 校园卡充值的取数接缝：界面只问"账户里有什么""这次开单了吗""付掉了没有"。
 *
 * 三步对应协议上的三段：查卡 → 开单（第三方支付入口）→ 对单支付。
 * 订单号藏在开单响应的重定向 URL 里、支付响应体要先反序列化——这两件事都是 ycard 协议的形状，
 * 因此留在实现侧（生产实现见 :app 的适配器），界面只拿到问出来的答案。
 */
interface CardRechargeSource : MockDataSignals {

    /** 校园卡账户信息：卡列表与余额。 */
    suspend fun account(): AhuResult<CardInfo>

    /**
     * 按金额与卡类型开单，返回订单号。
     *
     * 上游可能给出一个没有订单号的响应：那不是异常，而是一次"没开成"，
     * 因此用 null 表达，由展示侧决定措辞（ADR 0001：文案归 UI 层）。
     */
    suspend fun openOrder(amount: String, cardType: String): AhuResult<String?>

    /**
     * 对已开的订单发起支付。
     *
     * 同样用 null 表达"响应里读不出支付结果"——搬迁前那一步会落到调用方自己的兜底文案上，
     * 端口把这种情形如实带出去，而不是替调用方编一句话。
     */
    suspend fun pay(orderId: String, bank: CardRechargeBank): AhuResult<PayResponse?>

    /** 上次选的充值方式（本机偏好，从未选过则为 null）。 */
    fun rechargeBank(): CardRechargeBank?

    /** 记住这次选的充值方式。 */
    fun saveRechargeBank(bank: CardRechargeBank)
}
