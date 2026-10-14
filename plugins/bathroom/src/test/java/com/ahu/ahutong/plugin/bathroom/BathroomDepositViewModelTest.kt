package com.ahu.ahutong.plugin.bathroom

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.plugin.bathroom.PayState
import com.ahu.ahutong.plugin.bathroom.BathroomTelInfo
import com.ahu.ahutong.plugin.bathroom.Data
import com.ahu.ahutong.plugin.bathroom.MapData
import com.ahu.ahutong.plugin.bathroom.RechargeReceipt
import com.ahu.ahutong.plugin.bathroom.FakeBathroomDepositSource
import com.ahu.ahutong.plugin.bathroom.FakeBehaviorRecorder
import com.ahu.ahutong.plugin.bathroom.FakePaymentKeyboardSetting
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * 浴室缴费 ViewModel 的契约测试。
 *
 * 取数换成了 [FakeBathroomDepositSource]（查询可抛异常、可挂起，提交缴费按顺序返回响应体），
 * 调度器换成测试调度器：用例因此完全确定，不连一卡通、不碰缓存、不需要设备。
 * 这里钉住的是查询的三种中断语义与"四步支付顺序"——后者在界面上看不出来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BathroomDepositViewModelTest {
    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        source: FakeBathroomDepositSource,
        behavior: FakeBehaviorRecorder = FakeBehaviorRecorder(),
        keyboard: FakePaymentKeyboardSetting = FakePaymentKeyboardSetting()
    ) = BathroomDepositViewModel(source, behavior, keyboard, dispatcher)

    @Test
    fun `timeout becomes a visible error and the next query can succeed`() = runTest(dispatcher) {
        val expected = successfulResponse()
        var attempts = 0
        val source = FakeBathroomDepositSource().apply {
            infoHook = { _, _ ->
                if (attempts++ == 0) throw SocketTimeoutException("timed out")
                expected
            }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()
        assertEquals("请求超时，请重试", viewModel.queryError.value)
        assertNull(viewModel.info.value)
        assertFalse(viewModel.isQuerying.value)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        assertNull(viewModel.queryError.value)
        assertTrue(viewModel.isQuerying.value)
        runCurrent()
        assertSame(expected, viewModel.info.value)
        assertNull(viewModel.queryError.value)
        assertFalse(viewModel.isQuerying.value)
    }

    @Test
    fun `credential failure without data does not expose an invalid account`() = runTest(dispatcher) {
        val source = FakeBathroomDepositSource().apply {
            infoHook = { _, _ -> AhuResult.Failure(AhuError.Server(-1, "校园卡登录凭证暂未就绪")) }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()

        assertNull(viewModel.info.value)
        assertEquals("校园卡登录凭证暂未就绪", viewModel.queryError.value)
        assertFalse(viewModel.isQuerying.value)
    }

    @Test
    fun `cancelled previous request cannot clear the replacement loading state`() = runTest(dispatcher) {
        val previous = CompletableDeferred<AhuResult<BathroomTelInfo>>()
        val replacement = CompletableDeferred<AhuResult<BathroomTelInfo>>()
        val expected = successfulResponse()
        val source = FakeBathroomDepositSource().apply {
            infoHook = { bathroom, _ ->
                if (bathroom == "竹园/龙河") {
                    // Model an underlying operation that finishes after cancellation.
                    withContext(NonCancellable) { previous.await() }
                } else {
                    replacement.await()
                }
            }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()
        viewModel.getBathroomInfo("桔园/蕙园", PHONE)
        runCurrent()
        previous.complete(successfulResponse())
        runCurrent()

        assertTrue(viewModel.isQuerying.value)
        assertNull(viewModel.info.value)
        assertNull(viewModel.queryError.value)

        replacement.complete(expected)
        runCurrent()
        assertSame(expected, viewModel.info.value)
        assertFalse(viewModel.isQuerying.value)
    }

    @Test
    fun `clearing an in flight lookup prevents a late account from reappearing`() = runTest(dispatcher) {
        val response = CompletableDeferred<AhuResult<BathroomTelInfo>>()
        val source = FakeBathroomDepositSource().apply {
            infoHook = { _, _ -> withContext(NonCancellable) { response.await() } }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()
        viewModel.clearBathroomInfo()
        response.complete(successfulResponse())
        runCurrent()

        assertNull(viewModel.info.value)
        assertNull(viewModel.queryError.value)
        assertFalse(viewModel.isQuerying.value)
    }

    @Test
    fun `coroutine cancellation is not reported as a query failure`() = runTest(dispatcher) {
        val source = FakeBathroomDepositSource().apply {
            infoHook = { _, _ -> throw CancellationException("query cancelled") }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()

        assertNull(viewModel.queryError.value)
        assertNull(viewModel.info.value)
        assertFalse(viewModel.isQuerying.value)
    }

    @Test
    fun `payment failure from the source is visible`() = runTest(dispatcher) {
        val source = FakeBathroomDepositSource().apply {
            infoHook = { _, _ -> successfulAccountResponse() }
            paymentHook = { AhuResult.Failure(AhuError.Server(-1, "测试支付失败")) }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()
        viewModel.pay("竹园/龙河", "0.1", "012345")
        advanceUntilIdle()

        assertEquals(1, source.payments.size)
        assertEquals("0.1", source.payments.single().amount)
        assertEquals("012345", source.payments.single().password)
        assertEquals(PayState.Failed("测试支付失败"), viewModel.payState.value)
    }

    @Test
    fun `a successful payment publishes the receipt`() = runTest(dispatcher) {
        val source = FakeBathroomDepositSource().apply {
            infoHook = { _, _ -> successfulAccountResponse() }
            paymentHook = { AhuResult.Success(RechargeReceipt("order-1")) }
        }
        val viewModel = viewModel(source)

        viewModel.getBathroomInfo("竹园/龙河", PHONE)
        runCurrent()
        viewModel.pay("竹园/龙河", "0.1", "012345")
        advanceUntilIdle()

        assertEquals(PayState.Succeeded("order-1"), viewModel.payState.value)
        assertEquals(PHONE, source.payments.single().account.telPhone)
    }

    private fun successfulResponse() =
        AhuResult.Success(BathroomTelInfo(msg = "success", code = 200, map = null, message = null))

    private fun successfulAccountResponse() = AhuResult.Success(
        BathroomTelInfo(
            msg = "success",
            code = 200,
            map = MapData(
                showData = null,
                data = Data(
                    projectId = 945,
                    projectName = "安大浴室",
                    accountId = 1,
                    telPhone = PHONE,
                    identifier = null,
                    sex = "未知",
                    name = null,
                    statusId = 0,
                    accountMoney = 100,
                    accountGivenMoney = 0,
                    alias = null,
                    tags = null,
                    isCard = 0,
                    cardStatusId = -1,
                    isUseCode = 1,
                    cardPhysicalId = null,
                    tsmAbstract = "telPhone：${PHONE};竹园/龙河浴室",
                    myCustomInfo = null,
                    message = null
                )
            ),
            message = null
        )
    )

    private companion object {
        const val PHONE = "13800000000"
    }
}
