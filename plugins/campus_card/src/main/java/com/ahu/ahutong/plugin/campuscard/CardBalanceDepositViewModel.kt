package com.ahu.ahutong.plugin.campuscard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahu.ahutong.core.common.toUserMessage
import com.ahu.ahutong.plugin.campuscard.CardInfo
import com.ahu.ahutong.data.model.CardRechargeBank
import com.ahu.ahutong.plugin.campuscard.CardRechargeSource
import com.ahu.ahutong.data.session.SessionIdentity
import com.ahu.ahutong.ext.launchSafe
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 校园卡充值页的状态机。
 *
 * 取数只经 [CardRechargeSource]：协议的形状（订单号藏在重定向 URL 里、支付响应体要先解析）
 * 留在 :app 的适配器里，这里只剩「加载 → 开单 → 支付 → 成功/失败」的编排。
 * 迁移前它直接拿着 AHURepository 与 OkHttp 的 Response，因此只能在真机上点一遍；
 * 现在它由 JVM 测试驱动（见 CardBalanceDepositViewModelTest）。
 */
class CardBalanceDepositViewModel constructor(
    private val recharge: CardRechargeSource,
    private val session: SessionIdentity,
    private val behavior: BehaviorRecorder?
) : ViewModel() {

    /** 卡片要写上的姓名与学号：与迁移前的 remember { SessionStore.currentUser() } 同义，读一次。 */
    val campusCardName: String = session.currentUser()?.name.orEmpty()

    val campusCardId: String = session.currentUser()?.xh.orEmpty()

    private val _selectedBank = MutableStateFlow(recharge.rechargeBank())

    /** 当前选中的充值方式：界面不再自己持有它，选过的那次会记在本机。 */
    val selectedBank: StateFlow<CardRechargeBank?> = _selectedBank

    fun selectBank(bank: CardRechargeBank) {
        _selectedBank.value = bank
        recharge.saveRechargeBank(bank)
    }

    /**
     * 用户提交了一次充值。上报发生在 ViewModel 里，界面只发出意图——与课表、成绩、考试三页一致。
     * 招商银行卡走单独的动作 id，其余（农行、支付宝）共用一个，与迁移前的映射相同。
     */
    fun onRechargeSubmitted(bank: CardRechargeBank) {
        behavior?.recordOrganicAction(
            if (bank == CardRechargeBank.CHINA_MERCHANTS_BANK) {
                AppActionId.SUBMIT_CMB_CARD_RECHARGE
            } else {
                AppActionId.SUBMIT_CARD_RECHARGE
            }
        )
    }

    fun usesMockData(): Boolean = recharge.usesMockData()

    fun mockRefreshRevisions(): Flow<Long> = recharge.mockRefreshRevisions()

    private val _cardInfo = MutableStateFlow<CardInfo?>(null)

    val cardInfo: StateFlow<CardInfo?> = _cardInfo

    private val _accountState = MutableStateFlow<CardAccountState>(CardAccountState.Loading)
    val accountState: StateFlow<CardAccountState> = _accountState

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState

    fun load() = viewModelScope.launchSafe {
        _accountState.value = CardAccountState.Loading
        val response = recharge.account()
        val cardPayload = response.valueOrNull()
        if (cardPayload != null) {
            _cardInfo.value = cardPayload
            _accountState.value = CardAccountState.Ready(cardPayload)
        } else {
            _cardInfo.value = null
            _accountState.value = CardAccountState.Error(
                response.errorOrNull()?.toUserMessage() ?: "未获取到校园卡账户信息"
            )
        }
    }

    fun charge(value: String, bank: CardRechargeBank) = viewModelScope.launchSafe {

        _paymentState.value = PaymentState.Loading

        val accountInfo = when (val state = accountState.value) {
            is CardAccountState.Ready -> state.cardInfo.data.card.getOrNull(0)?.accinfo?.getOrNull(0)
            else -> null
        }

        if (accountInfo == null) {
            _paymentState.value = PaymentState.Error("异常: 未获取到用户信息")
            return@launchSafe
        }

        val response = recharge.openOrder(value, accountInfo.type)
        if (response.isFailure) {
            _paymentState.value = PaymentState.Error(
                response.errorOrNull()?.toUserMessage() ?: "未获取到订单信息"
            )
            return@launchSafe
        }

        val orderId = response.valueOrNull()
        if (orderId != null) {
            try {
                val payResult = recharge.pay(orderId, bank)
                if (payResult.isFailure) {
                    _paymentState.value = PaymentState.Error(
                        payResult.errorOrNull()?.toUserMessage() ?: "未获取到支付结果"
                    )
                    return@launchSafe
                }

                val payResponse = payResult.valueOrNull()
                if (payResponse != null) {
                    if (payResponse.code == 200) {
                        _paymentState.value = PaymentState.Success(payResponse.data)
                        load()
                        return@launchSafe
                    }
                    _paymentState.value = PaymentState.Error(payResponse.msg)
                    return@launchSafe
                }
            } catch (e: Exception) {
                _paymentState.value = PaymentState.Error("异常: " + e.message)
                return@launchSafe
            }
        }

        // 与搬迁前逐字一致：既没有订单号、也没有读得出的支付结果，都落到这一句。
        _paymentState.value = PaymentState.Error("异常: 未获取到订单号")
    }

    fun resetPaymentState() {
        _paymentState.value = PaymentState.Idle
    }
}

sealed class CardAccountState {
    object Loading : CardAccountState()
    data class Ready(val cardInfo: CardInfo) : CardAccountState()
    data class Error(val message: String) : CardAccountState()
}

sealed class PaymentState {
    object Idle : PaymentState()
    object Loading : PaymentState()
    data class Success(val orderId: String) : PaymentState()
    data class Error(val message: String) : PaymentState()
}
