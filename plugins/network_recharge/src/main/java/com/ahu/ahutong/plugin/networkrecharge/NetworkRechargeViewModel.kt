package com.ahu.ahutong.plugin.networkrecharge

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahu.ahutong.core.common.toUserMessage
import com.ahu.ahutong.core.storage.PaymentKeyboardSetting
import com.ahu.ahutong.plugin.networkrecharge.PayState
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSnapshot
import com.ahu.ahutong.plugin.networkrecharge.NetworkRechargeSource
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NetworkRechargeUiData(
    val feeName: String,
    val account: String,
    val stats: List<Pair<String, String>>,
    val quickAmounts: List<String>,
    val maxAmount: String?
)

sealed class NetworkRechargePageState {
    object Loading : NetworkRechargePageState()
    data class Ready(val data: NetworkRechargeUiData) : NetworkRechargePageState()
    data class Error(val message: String) : NetworkRechargePageState()
}

class NetworkRechargeViewModel constructor(
    private val source: NetworkRechargeSource,
    private val behavior: BehaviorRecorder?,
    settings: PaymentKeyboardSetting
) : ViewModel() {

    val builtInKeyboard: StateFlow<Boolean?> = settings.useBuiltInSecurePasswordKeyboard
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onRechargeSubmitted() {
        behavior?.recordOrganicAction(AppActionId.SUBMIT_NETWORK_RECHARGE)
    }

    private val _pageState =
        MutableStateFlow<NetworkRechargePageState>(NetworkRechargePageState.Loading)
    val pageState: StateFlow<NetworkRechargePageState> = _pageState.asStateFlow()

    private val _payState = MutableStateFlow<PayState>(PayState.Idle)
    val payState: StateFlow<PayState> = _payState.asStateFlow()

    private var snapshot: NetworkRechargeSnapshot? = null

    fun load() {
        viewModelScope.launch {
            _pageState.value = NetworkRechargePageState.Loading
            _payState.value = PayState.Idle
            snapshot = null
            try {
                val result = source.load()
                val loaded = result.valueOrNull()
                if (loaded == null) {
                    _pageState.value = NetworkRechargePageState.Error(
                        result.errorOrNull()?.toUserMessage()?.takeIf { it.isNotBlank() }
                            ?: "网费充值信息加载失败"
                    )
                    return@launch
                }

                snapshot = loaded
                val priorityKeys = setOf(
                    "用户状态",
                    "储值余额",
                    "本期已使用费用",
                    "本期已使用时长",
                    "本期已使用流量"
                )
                val stats = mutableListOf<Pair<String, String>>().apply {
                    priorityKeys.forEach { key -> loaded.showData[key]?.let { add(key to it) } }
                    addAll(
                        loaded.showData.entries
                            .filterNot { it.key in priorityKeys }
                            .map { it.key to it.value }
                    )
                }
                val feeItem = loaded.feeItem
                _pageState.value = NetworkRechargePageState.Ready(
                    NetworkRechargeUiData(
                        feeName = feeItem.name ?: "网费充值",
                        account = loaded.paymentData.account.orEmpty(),
                        stats = stats,
                        quickAmounts = feeItem.layout
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            .orEmpty(),
                        maxAmount = feeItem.maxMoney ?: feeItem.dayMaxMoney
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to load network recharge info", error)
                _pageState.value = NetworkRechargePageState.Error(
                    error.message ?: "网费充值信息加载失败"
                )
            }
        }
    }

    fun pay(amount: String, password: String) {
        if (amount.toDoubleOrNull() ?: 0.0 <= 0) {
            _payState.value = PayState.Failed("请输入有效金额")
            return
        }
        if (password.length != 6 || !password.all(Char::isDigit)) {
            _payState.value = PayState.Failed("请输入6位校园卡密码")
            return
        }
        val loaded = snapshot
        if (_pageState.value !is NetworkRechargePageState.Ready || loaded == null) {
            _payState.value = PayState.Failed("网费账户信息尚未加载完成")
            return
        }

        _payState.value = PayState.InProgress
        viewModelScope.launch {
            try {
                val result = source.pay(loaded, amount, password)
                val receipt = result.valueOrNull()
                _payState.value = if (receipt != null) {
                    PayState.Succeeded(receipt.reference)
                } else {
                    PayState.Failed(
                        result.errorOrNull()?.toUserMessage()?.takeIf { it.isNotBlank() }
                            ?: "网费充值失败"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to pay network recharge", error)
                _payState.value = PayState.Failed(
                    error.message ?: "网费充值失败，请稍后重试"
                )
            }
        }
    }

    fun resetPayState() {
        _payState.value = PayState.Idle
    }

    private companion object {
        const val TAG = "NetworkRechargeViewModel"
    }
}
