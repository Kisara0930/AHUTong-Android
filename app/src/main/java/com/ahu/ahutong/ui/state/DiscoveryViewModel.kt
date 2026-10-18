package com.ahu.ahutong.ui.state

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahu.ahutong.core.storage.CampusCardModeSetting
import com.ahu.ahutong.data.AHURepository
import com.ahu.ahutong.data.dao.AHUCache
import com.ahu.ahutong.data.model.CampusCardProvider
import com.ahu.ahutong.ext.launchSafe
import com.ahu.ahutong.data.payment.PaymentQrRepository
import com.ahu.ahutong.data.payment.PaymentQrOwner
import com.ahu.ahutong.data.session.AhuSession
import com.ahu.ahutong.data.session.AhuSessionState
import com.ahu.ahutong.personalization.runtime.BehaviorPredictionRuntime
import com.ahu.ahutong.personalization.context.BalanceBucket
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.ahu.ahutong.core.common.onSuccess

/**
 * @Author Simon
 * @Date 2021/8/3-22:12
 * @Email 330771794@qq.com
 */


@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val paymentQrRepository: PaymentQrRepository,
    private val behaviorRuntime: BehaviorPredictionRuntime,
    private val session: AhuSession,
    private val campusCardMode: CampusCardModeSetting
) : ViewModel() {

    val TAG = DiscoveryViewModel::class.java.simpleName

    val bathroom = mutableStateMapOf<String, String>()
    var balance by mutableStateOf(0.0)
    var transitionBalance by mutableStateOf(0.0)

    /**
     * 当前数据源。初值未知（null）：首帧按安大读缓存，等设置落地后如果其实是自定义数据源，
     * 会走一次切源重置，清掉上一数据源的余额再读新数据源自己的缓存。
     */
    @Volatile
    private var cardProvider: CampusCardProvider? = null

    val qrcode = MutableStateFlow<Bitmap?>(null)
    val state = MutableStateFlow(false)
    val qrStatusText = MutableStateFlow("")
    val qrRefreshing = MutableStateFlow(false)
    private val qrLoader = PaymentQrLoader(paymentQrRepository, viewModelScope, ::refreshCardBalance)
    private var qrWarmJob: Job? = null
    private var warmOwner: PaymentQrOwner? = null
    private var renderedQrKey: Pair<String, Long>? = null
    private var qrRenderFailed = false

    init {
        viewModelScope.launch {
            campusCardMode.campusCardProvider.collect { provider ->
                // 首帧按安大读缓存，因此这里的"上一个"缺省按安大算：若真实数据源是自定义，
                // 第一次落地也要重置，否则自定义用户会一直看到安大缓存的余额。
                val previous = cardProvider ?: CampusCardProvider.AHU
                if (previous == provider) {
                    cardProvider = provider
                    return@collect
                }
                cardProvider = provider
                // 切换数据源后内存里仍是上一数据源的余额：必须立刻清掉再读新数据源自己的缓存，
                // 否则首页会显示别校/别系统的金额，且在没有下一次成功请求前无法纠正。
                resetCachedBalance(provider)
            }
        }
        viewModelScope.launch {
            session.state.collectLatest { status ->
                if (status == AhuSessionState.Status.Anonymous && paymentQrRepository.currentOwner() == null) {
                    clearQrCode()
                    qrWarmJob?.cancel()
                    warmOwner = null
                }
            }
        }
        viewModelScope.launch {
            qrLoader.state.collectLatest { value ->
                val snapshot = value.snapshot
                val key = snapshot?.let { it.payload to it.fetchedAtEpochMs }
                if (key != renderedQrKey) {
                    qrRenderFailed = false
                    val bitmap = try {
                        snapshot?.let {
                            withContext(Dispatchers.IO) {
                                BarcodeEncoder().encodeBitmap(
                                    it.payload, BarcodeFormat.QR_CODE, 400, 400,
                                    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1)
                                )
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        qrRenderFailed = true
                        Log.w("QR", "Unable to render payment QR")
                        null
                    }
                    qrcode.value = bitmap
                    renderedQrKey = key
                }
                qrStatusText.value = if (qrRenderFailed) "二维码数据暂不可用，请重试" else value.statusText
                qrRefreshing.value = value.loading
                state.value = value.attempted || snapshot != null
            }
        }
    }

    /** Start before profile/native initialization and let the ordinary balance work wait. */
    fun preparePaymentQr() {
        if (AHUCache.getMockData()) return
        val owner = paymentQrRepository.currentOwner() ?: return
        if (warmOwner == owner) return
        warmOwner = owner
        qrWarmJob = viewModelScope.launchSafe { paymentQrRepository.prepareForForeground() }
    }

    suspend fun awaitPaymentQrPreparation() { qrWarmJob?.join() }

    fun loadActivityBean() {
        preparePaymentQr()
        val requestProvider = currentProvider()
        // 优先加载缓存
        if (!AHUCache.getMockData()) {
            AHUCache.getCardBalance(requestProvider)?.let {
                balance = it
            }
        }

        viewModelScope.launchSafe {
            qrWarmJob?.join()
            val (cardResult, bathroomResult) = coroutineScope {
                val card = async { AHURepository.getCardMoney() }
                val bathrooms = async { AHURepository.getBathRooms() }
                card.await() to bathrooms.await()
            }
            cardResult.onSuccess {
                // 期间切了数据源就丢弃这次结果：否则会把上一数据源的余额写进当前数据源。
                if (currentProvider() != requestProvider) return@onSuccess
                applyCardBalance(it.balance, it.transitionBalance, requestProvider)
            }
            bathroomResult.onSuccess {
                bathroom.clear()
                it.forEach { room ->
                    bathroom += room.bathroom to room.openStatus
                }
            }
        }
    }

    fun refreshCardBalance() {
        val requestProvider = currentProvider()
        if (!AHUCache.getMockData()) {
            AHUCache.getCardBalance(requestProvider)?.let {
                balance = it
            }
        }

        viewModelScope.launchSafe {
            qrWarmJob?.join()
            AHURepository.getCardMoney().onSuccess {
                if (currentProvider() != requestProvider) return@onSuccess
                applyCardBalance(it.balance, it.transitionBalance, requestProvider)
            }
        }
    }

    private fun currentProvider(): CampusCardProvider = cardProvider ?: CampusCardProvider.AHU

    /** 切源时清掉内存里的上一数据源余额，并用当前数据源自己的缓存重建。 */
    private fun resetCachedBalance(provider: CampusCardProvider) {
        transitionBalance = 0.0
        balance = if (AHUCache.getMockData()) 0.0 else AHUCache.getCardBalance(provider) ?: 0.0
    }

    private fun applyCardBalance(
        balanceValue: Double?,
        transitionBalanceValue: Double?,
        provider: CampusCardProvider
    ) {
        val newBalance = balanceValue ?: 0.0
        balance = newBalance
        AHUCache.saveCardBalance(newBalance, provider)
        // 自定义数据源没有"在途金额"概念：显式清空，避免沿用上一数据源的在途金额并排显示。
        transitionBalance = if (provider == CampusCardProvider.CUSTOM) {
            0.0
        } else {
            transitionBalanceValue ?: transitionBalance
        }
        // 自定义数据源的余额不属于本应用的个性化画像：跳过余额桶写入，避免污染行为画像。
        if (provider == CampusCardProvider.CUSTOM) return
        behaviorRuntime.onBusinessContextChanged(
            newBalanceBucket = when {
                newBalance < 5.0 -> BalanceBucket.ZERO_TO_FIVE
                newBalance < 10.0 -> BalanceBucket.FIVE_TO_TEN
                newBalance < 20.0 -> BalanceBucket.TEN_TO_TWENTY
                newBalance < 50.0 -> BalanceBucket.TWENTY_TO_FIFTY
                else -> BalanceBucket.FIFTY_PLUS
            },
            newBalanceFresh = true
        )
    }

    fun loadQrCode(forceRefresh: Boolean = false) {
        qrLoader.start(forceRefresh)
    }

    fun clearQrCode() {
        qrLoader.stop()
        renderedQrKey = null
        qrRenderFailed = false
        qrcode.value = null
        state.value = false
        qrStatusText.value = ""
        qrRefreshing.value = false
    }

}
