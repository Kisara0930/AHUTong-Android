package com.ahu.ahutong.plugin.campuscard

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.campuscard.CardInfo
import com.ahu.ahutong.plugin.campuscard.PayResponse
import com.ahu.ahutong.data.model.CardRechargeBank
import com.ahu.ahutong.plugin.campuscard.CardRechargeSource
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [CardRechargeSource] 的假实现：三步各自可编排，并记下调用次数与参数。
 *
 * 它们让校园卡充值的三段流程第一次能在 JVM 上走完——迁移前那段逻辑直接拿着
 * AHURepository 与 OkHttp 的 Response，只能在真机上点。
 */
class FakeCardRechargeSource(
    var accountResult: AhuResult<CardInfo> = AhuResult.Success(fakeCardInfo()),
    var openOrderResult: AhuResult<String?> = AhuResult.Success("order-1"),
    var payResult: AhuResult<PayResponse?> =
        AhuResult.Success(PayResponse(code = 200, `data` = "order-1", msg = "ok", success = true))
) : CardRechargeSource {

    var accountCalls = 0
        private set
    var openOrderCalls = 0
        private set
    var payCalls = 0
        private set

    var lastAmount: String? = null
        private set
    var lastCardType: String? = null
        private set
    var lastOrderId: String? = null
        private set
    var lastBank: CardRechargeBank? = null
        private set

    /** 本机记住的充值方式：saveRechargeBank 写它，rechargeBank 读它。 */
    var bank: CardRechargeBank? = null
        private set

    var mockData = false

    val mockRevisions = MutableStateFlow(0L)

    override suspend fun account(): AhuResult<CardInfo> {
        accountCalls++
        return accountResult
    }

    override suspend fun openOrder(amount: String, cardType: String): AhuResult<String?> {
        openOrderCalls++
        lastAmount = amount
        lastCardType = cardType
        return openOrderResult
    }

    override suspend fun pay(orderId: String, bank: CardRechargeBank): AhuResult<PayResponse?> {
        payCalls++
        lastOrderId = orderId
        lastBank = bank
        return payResult
    }

    override fun rechargeBank(): CardRechargeBank? = bank

    override fun saveRechargeBank(bank: CardRechargeBank) {
        this.bank = bank
    }

    override fun usesMockData(): Boolean = mockData

    override fun mockRefreshRevisions(): Flow<Long> = mockRevisions
}

/**
 * 校园卡账户 fixture：按线上 JSON 造，而不是手工拼四十多个字段——
 * 字段名与嵌套形状本就是协议契约的一部分，用 Gson 解析顺带证明搬迁没有改变它。
 */
fun fakeCardInfo(cardType: String = "1", balance: Int = 12345): CardInfo =
    Gson().fromJson(
        CARD_JSON.replace("__TYPE__", cardType).replace("__BALANCE__", balance.toString()),
        CardInfo::class.java
    )

private val CARD_JSON = """
{
  "code": 0,
  "msg": "ok",
  "success": true,
  "data": {
    "account": null,
    "errmsg": null,
    "retcode": "0",
    "sno": null,
    "card": [
      {
        "acc_status": 0,
        "account": "2021001",
        "acctId": null,
        "auth_code_background": null,
        "auth_code_font_color": null,
        "autotrans_amt": 0,
        "autotrans_flag": 0,
        "autotrans_limite": 0,
        "bankacc": "",
        "barflag": 0,
        "card_background": null,
        "card_font_color": null,
        "card_logo": "",
        "card_name": "校园卡",
        "card_name_en": "Campus Card",
        "cardname": "校园卡",
        "cardtype": "__TYPE__",
        "cert": "",
        "createdate": "",
        "custId": null,
        "custMemberId": null,
        "daycostlimit": 0,
        "db_balance": 0,
        "debitamt": 0,
        "department_name": null,
        "elec_accamt": 0,
        "expdate": "",
        "flag": "",
        "freezeflag": 0,
        "idflag": 0,
        "lostflag": 0,
        "mscard": 0,
        "name": "张三",
        "nonpwdlimit": 0,
        "phone": "",
        "scbkbs": 0,
        "schcode": "",
        "singlelimit": 0,
        "sno": "2021001",
        "unsettle_amount": 0,
        "voucher": "",
        "voucherStatus": 0,
        "accinfo": [
          {
            "autotrans_amt": null,
            "autotrans_flag": 0,
            "autotrans_limite": null,
            "balance": __BALANCE__,
            "daycostamt": null,
            "daycostlimit": null,
            "name": "电子账户",
            "nonpwdlimit": null,
            "singlelimit": null,
            "type": "__TYPE__"
          }
        ]
      }
    ]
  }
}
""".trimIndent()
