package com.ahu.ahutong.plugin.campuscard

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.toUserMessage
import com.ahu.ahutong.plugin.campuscard.PayResponse
import com.ahu.ahutong.data.model.CardRechargeBank
import com.ahu.ahutong.data.model.User
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.plugin.campuscard.FakeBehaviorRecorder
import com.ahu.ahutong.plugin.campuscard.FakeCardRechargeSource
import com.ahu.ahutong.plugin.campuscard.FakeSessionIdentity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule

/**
 * 校园卡充值 ViewModel 的契约测试：取数换成了 [FakeCardRechargeSource]，因此不连一卡通、
 * 不碰会话，也不需要设备。
 *
 * 这里钉住的是充值三段流程的分支与**用户可见文案**——尤其是"开单没给出订单号"与
 * "支付响应读不出结果"都落到同一句兜底文案，那是搬迁前就有的行为，这一轮刻意保持不变。
 * 用 UnconfinedTestDispatcher：写入与收集立即完成，断言可以直接读结果。
 */
class CardBalanceDepositViewModelTest {

    @get:Rule
    val instantTaskExecutor = InstantTaskExecutorRule()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        recharge: FakeCardRechargeSource = FakeCardRechargeSource(),
        session: FakeSessionIdentity = FakeSessionIdentity(),
        behavior: FakeBehaviorRecorder = FakeBehaviorRecorder()
    ) = CardBalanceDepositViewModel(recharge, session, behavior)

    @Test
    fun `loading the account publishes the card and the ready state`() {
        val fake = FakeCardRechargeSource()
        val subject = viewModel(recharge = fake)

        subject.load()

        val state = assertIs<CardAccountState.Ready>(subject.accountState.value)
        assertEquals(12345, state.cardInfo.data.card[0].accinfo[0].balance)
        assertEquals("1", state.cardInfo.data.card[0].accinfo[0].type)
        assertEquals(state.cardInfo, subject.cardInfo.value)
        assertEquals(1, fake.accountCalls)
    }

    @Test
    fun `a failed account load keeps the error model wording`() {
        val fake = FakeCardRechargeSource(accountResult = AhuResult.Failure(AhuError.Network))
        val subject = viewModel(recharge = fake)

        subject.load()

        val state = assertIs<CardAccountState.Error>(subject.accountState.value)
        assertEquals(AhuError.Network.toUserMessage(), state.message)
        assertEquals(null, subject.cardInfo.value)
    }

    @Test
    fun `charging before the account is loaded reports the missing account`() {
        val fake = FakeCardRechargeSource()
        val subject = viewModel(recharge = fake)

        subject.charge("50", CardRechargeBank.ALIPAY)

        assertEquals(PaymentState.Error("异常: 未获取到用户信息"), subject.paymentState.value)
        assertEquals(0, fake.openOrderCalls)
        assertEquals(0, fake.payCalls)
    }

    @Test
    fun `charging opens the order with the card type, pays it and reloads the account`() {
        val fake = FakeCardRechargeSource()
        val subject = viewModel(recharge = fake)
        subject.load()

        subject.charge("50", CardRechargeBank.CHINA_MERCHANTS_BANK)

        assertEquals(PaymentState.Success("order-1"), subject.paymentState.value)
        assertEquals("50", fake.lastAmount)
        assertEquals("1", fake.lastCardType)
        assertEquals("order-1", fake.lastOrderId)
        assertEquals(CardRechargeBank.CHINA_MERCHANTS_BANK, fake.lastBank)
        assertEquals(2, fake.accountCalls)
    }

    @Test
    fun `an order without an order id keeps the legacy wording and never pays`() {
        val fake = FakeCardRechargeSource(openOrderResult = AhuResult.Success(null))
        val subject = viewModel(recharge = fake)
        subject.load()

        subject.charge("50", CardRechargeBank.ALIPAY)

        assertEquals(PaymentState.Error("异常: 未获取到订单号"), subject.paymentState.value)
        assertEquals(0, fake.payCalls)
    }

    @Test
    fun `a failed payment surfaces the error model wording`() {
        val fake = FakeCardRechargeSource(payResult = AhuResult.Failure(AhuError.Timeout))
        val subject = viewModel(recharge = fake)
        subject.load()

        subject.charge("50", CardRechargeBank.ALIPAY)

        assertEquals(PaymentState.Error(AhuError.Timeout.toUserMessage()), subject.paymentState.value)
    }

    @Test
    fun `a payment response without a readable result keeps the legacy wording`() {
        val fake = FakeCardRechargeSource(payResult = AhuResult.Success(null))
        val subject = viewModel(recharge = fake)
        subject.load()

        subject.charge("50", CardRechargeBank.ALIPAY)

        assertEquals(PaymentState.Error("异常: 未获取到订单号"), subject.paymentState.value)
    }

    @Test
    fun `a rejected payment shows the upstream message`() {
        val fake = FakeCardRechargeSource(
            payResult = AhuResult.Success(
                PayResponse(code = 500, `data` = "", msg = "余额不足", success = false)
            )
        )
        val subject = viewModel(recharge = fake)
        subject.load()

        subject.charge("50", CardRechargeBank.ALIPAY)

        assertEquals(PaymentState.Error("余额不足"), subject.paymentState.value)
    }

    @Test
    fun `selecting a bank is remembered on the device`() {
        val fake = FakeCardRechargeSource()
        val subject = viewModel(recharge = fake)

        subject.selectBank(CardRechargeBank.AGRICULTURAL_BANK)

        assertEquals(CardRechargeBank.AGRICULTURAL_BANK, subject.selectedBank.value)
        assertEquals(CardRechargeBank.AGRICULTURAL_BANK, fake.bank)
    }

    @Test
    fun `the remembered bank is the one the screen starts with`() {
        val subject = viewModel(
            recharge = FakeCardRechargeSource().apply { saveRechargeBank(CardRechargeBank.ALIPAY) }
        )

        assertEquals(CardRechargeBank.ALIPAY, subject.selectedBank.value)
    }

    @Test
    fun `submitting reports the bank specific action`() {
        val behavior = FakeBehaviorRecorder()
        val subject = viewModel(behavior = behavior)

        subject.onRechargeSubmitted(CardRechargeBank.CHINA_MERCHANTS_BANK)
        subject.onRechargeSubmitted(CardRechargeBank.AGRICULTURAL_BANK)
        subject.onRechargeSubmitted(CardRechargeBank.ALIPAY)

        assertEquals(
            listOf(
                AppActionId.SUBMIT_CMB_CARD_RECHARGE,
                AppActionId.SUBMIT_CARD_RECHARGE,
                AppActionId.SUBMIT_CARD_RECHARGE
            ),
            behavior.organicActions
        )
    }

    @Test
    fun `the card shows the current user identity`() {
        val subject = viewModel(
            session = FakeSessionIdentity(user = User("张三", "2021001"))
        )

        assertEquals("张三", subject.campusCardName)
        assertEquals("2021001", subject.campusCardId)
    }
}
