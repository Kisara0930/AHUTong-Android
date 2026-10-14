package com.ahu.ahutong.plugin.networkrecharge

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.networkrecharge.PayState
import com.ahu.ahutong.plugin.networkrecharge.NetworkFeeItem
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSnapshot
import com.ahu.ahutong.plugin.networkrecharge.NetworkThirdPartyData
import com.ahu.ahutong.plugin.networkrecharge.RechargeReceipt
import com.ahu.ahutong.plugin.networkrecharge.FakeBehaviorRecorder
import com.ahu.ahutong.plugin.networkrecharge.FakeNetworkRechargeSource
import com.ahu.ahutong.plugin.networkrecharge.FakePaymentKeyboardSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRechargeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load and pay cross one typed seam`() = runTest(dispatcher) {
        val snapshot = snapshot()
        val source = FakeNetworkRechargeSource(
            loadResult = AhuResult.Success(snapshot),
            payResult = AhuResult.Success(RechargeReceipt("order-1"))
        )
        val subject = NetworkRechargeViewModel(
            source,
            FakeBehaviorRecorder(),
            FakePaymentKeyboardSetting()
        )

        subject.load()
        advanceUntilIdle()
        val page = subject.pageState.value
        assertTrue(page is NetworkRechargePageState.Ready)
        assertEquals("20210001", page.data.account)

        subject.pay("10", "012345")
        advanceUntilIdle()

        assertEquals(PayState.Succeeded("order-1"), subject.payState.value)
        assertEquals("10", source.payments.single().amount)
        assertEquals("012345", source.payments.single().password)
    }

    private fun snapshot() = NetworkRechargeSnapshot(
        feeItem = NetworkFeeItem("network", "10,20", "100", null, "1"),
        showData = linkedMapOf("用户状态" to "正常"),
        paymentData = NetworkThirdPartyData(
            stateTime = null,
            stateMemo = null,
            balance = "20",
            useTime = null,
            tsmAbstract = "account",
            useMoney = null,
            useFlow = null,
            account = "20210001",
            userState = null,
            startDate = null
        )
    )
}
