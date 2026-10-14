package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.map
import com.ahu.ahutong.plugin.campuscard.CardBalanceRequest
import com.ahu.ahutong.plugin.campuscard.CardInfo
import com.ahu.ahutong.plugin.campuscard.CardPayRequest
import com.ahu.ahutong.plugin.campuscard.PayResponse
import com.ahu.ahutong.data.model.CardRechargeBank
import com.ahu.ahutong.plugin.campuscard.CardRechargeSource
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [CardRechargeSource] 的生产实现：三个动作转给 AHURepository。
 *
 * 协议的两处形状留在这里：订单号要从开单响应的重定向 URL 里取、支付响应体要先反序列化。
 * 搬迁前这两段写在 ViewModel 里（它还因此拿到了 OkHttp 的 Response），现在它们回到协议侧。
 * 解析留在 IO 线程：搬迁前是 ViewModel 的 withContext(Dispatchers.IO) 覆盖着这两步，
 * 现在改由实现侧自己调度——端口的实现负责线程，调用方只管编排。
 */
class RepositoryCardRechargeSource(private val gateway: CampusCardGateway, private val cache: CampusCardCache) : CardRechargeSource {

    override suspend fun account(): AhuResult<CardInfo> = gateway.getCardInfo()

    override suspend fun openOrder(amount: String, cardType: String): AhuResult<String?> =
        withContext(Dispatchers.IO) {
            gateway.getOrderThirdData(CardBalanceRequest(amount, cardType)).map { response ->
                ORDER_ID.find(response.raw().request.url.toString())?.groupValues?.get(1)
            }
        }

    override suspend fun pay(orderId: String, bank: CardRechargeBank): AhuResult<PayResponse?> =
        withContext(Dispatchers.IO) {
            gateway.pay(CardPayRequest(orderId, bank)).map { response ->
                response.body()?.let { body -> Gson().fromJson(body.string(), PayResponse::class.java) }
            }
        }

    override fun rechargeBank(): CardRechargeBank? = cache.getCardRechargeBank()

    override fun saveRechargeBank(bank: CardRechargeBank) = cache.setCardRechargeBank(bank)

    override fun usesMockData(): Boolean = false

    override fun mockRefreshRevisions(): Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    private companion object {
        val ORDER_ID = Regex("[?]orderid=([^&]+)")
    }
}
