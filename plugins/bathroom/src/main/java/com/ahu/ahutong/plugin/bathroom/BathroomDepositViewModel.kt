package com.ahu.ahutong.plugin.bathroom

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.core.common.toUserMessage
import com.ahu.ahutong.core.storage.PaymentKeyboardSetting
import com.ahu.ahutong.plugin.bathroom.PayState
import com.ahu.ahutong.plugin.bathroom.BathroomTelInfo
import com.ahu.ahutong.plugin.bathroom.BathroomDepositSource
import com.ahu.ahutong.plugin.bathroom.BathroomPayment
import com.ahu.ahutong.ext.launchSafe
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IO 调度器限定符：生产注入 Dispatchers.IO，测试注入测试调度器，让用例完全确定。 */
@Retention(AnnotationRetention.BINARY)
annotation class RechargeDispatcher

class BathroomDepositViewModel constructor(
    private val source: BathroomDepositSource,
    private val behavior: BehaviorRecorder?,
    settings: PaymentKeyboardSetting,
    @RechargeDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    val TAG = "BathroomDepositViewModel"

    /** 支付密码键盘：null 表示设置还没读出来（界面此时不渲染对话框，与迁移前一致）。 */
    val builtInKeyboard: StateFlow<Boolean?> = settings.useBuiltInSecurePasswordKeyboard
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 上次用过的手机号（界面预填用）。 */
    fun savedPhone(): String? = source.savedPhone()

    /** 用户确认了一次缴费：上报发生在 ViewModel 里，界面只发出意图。 */
    fun onPaymentSubmitted() {
        behavior?.recordOrganicAction(AppActionId.CONFIRM_BATHROOM_PAYMENT)
    }

    private  val _info = MutableStateFlow<AhuResult<BathroomTelInfo>?>(null)

    val info: StateFlow<AhuResult<BathroomTelInfo>?> = _info

    private val _isQuerying = MutableStateFlow(false)
    val isQuerying: StateFlow<Boolean> = _isQuerying

    private val _queryError = MutableStateFlow<String?>(null)
    val queryError: StateFlow<String?> = _queryError

    private var queryJob: Job? = null
    private var queryGeneration = 0L

    var _payState = MutableStateFlow<PayState>(PayState.Idle)

    val payState : StateFlow<PayState> = _payState

    fun resetPaymentState() {
        _payState.value = PayState.Idle
    }

    fun clearBathroomInfo() {
        queryGeneration++
        queryJob?.cancel()
        queryJob = null
        _isQuerying.value = false
        _info.value = null
        _queryError.value = null
    }

    fun getBathroomInfo(bathroom: String, tel: String) {
        if (tel.length != 11) {
            clearBathroomInfo()
            return
        }
        val generation = ++queryGeneration
        queryJob?.cancel()
        _isQuerying.value = true
        _info.value = null
        _queryError.value = null
        queryJob = viewModelScope.launch {
            try {
                val response = withContext(ioDispatcher) {
                    source.bathroomInfo(bathroom, tel)
                }
                if (generation == queryGeneration) {
                    if (response.isSuccess && response.valueOrNull() != null) {
                        _info.value = response
                    } else {
                        _queryError.value = response.errorOrNull()?.toUserMessage()
                            ?.takeIf { it.isNotBlank() }
                            ?: "未查询到浴室账户，请重试"
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == queryGeneration) {
                    _queryError.value = when (error) {
                        is UnknownHostException -> "网络不可用，请检查网络连接"
                        is SocketTimeoutException -> "请求超时，请重试"
                        is IOException -> "网络连接失败，请重试"
                        else -> "浴室账户查询失败，请重试"
                    }
                }
            } finally {
                if (generation == queryGeneration) {
                    _isQuerying.value = false
                }
            }
        }
    }



    val paymentSuccessEvent = MutableLiveData<Unit>()
    fun pay(bathroom: String, amount: String, password: String) {
        _payState.value = PayState.InProgress
        val accountData = info.value?.valueOrNull()?.map?.data
        if (accountData == null) {
            _payState.value = PayState.Failed("请先查询有效的浴室账户")
            return
        }
        val paymentQueryGeneration = queryGeneration

        viewModelScope.launchSafe {
            try {
                val result = withContext(ioDispatcher) {
                    source.pay(
                        BathroomPayment(
                            bathroom = bathroom,
                            amount = amount,
                            password = password,
                            account = accountData
                        )
                    )
                }
                val receipt = result.valueOrNull()
                if (receipt == null) {
                    _payState.value = PayState.Failed(
                        result.errorOrNull()?.toUserMessage()?.takeIf { it.isNotBlank() }
                            ?: "浴室缴费失败，请重试"
                    )
                    return@launchSafe
                }
                _payState.value = PayState.Succeeded(message = receipt.reference)
                paymentSuccessEvent.postValue(Unit)
                delay(1_000)
                if (paymentQueryGeneration == queryGeneration) {
                    getBathroomInfo(bathroom, accountData.telPhone)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _payState.value = PayState.Failed(
                    when (error) {
                        is UnknownHostException -> "网络不可用，请检查网络连接"
                        is SocketTimeoutException -> "创建订单 超时，请先核对余额后再重试"
                        is IOException -> "网络连接失败，请重试"
                        else -> error.message?.takeIf { it.isNotBlank() } ?: "浴室缴费失败，请重试"
                    }
                )
            }
        }
    }

}
