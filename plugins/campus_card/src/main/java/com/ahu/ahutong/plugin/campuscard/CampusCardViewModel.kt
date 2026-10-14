package com.ahu.ahutong.plugin.campuscard

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class CampusCardViewModel(private val gateway: CampusCardGateway, private val cache: CampusCardCache,
    private val qr: PaymentQrRepository) : ViewModel() {
    var balance by mutableStateOf(cache.balance() ?: 0.0)
        private set
    val transitionBalance = 0.0
    val qrcode = MutableStateFlow<Bitmap?>(null)
    val state = MutableStateFlow(false)
    val showDefaultQr = MutableStateFlow(cache.showQr())
    private var qrJob: Job? = null
    init { refreshCardBalance() }
    fun setShowDefaultQr(value: Boolean) { cache.saveShowQr(value); showDefaultQr.value = value }
    fun refreshCardBalance() {
        viewModelScope.launch {
            try { gateway.getBalance().let { balance = it; cache.saveBalance(it) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the last verified balance during a temporary outage. */ }
        }
    }
    fun loadQrCode(forceRefresh: Boolean = false) {
        if (!forceRefresh && qrJob?.isActive == true) return
        qrJob?.cancel(); state.value = false
        qrJob = viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    val text = qr.getForDisplay(forceRefresh).getOrThrow()
                    val bits = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400,
                        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1))
                    Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
                        val pixels = IntArray(160_000) { index -> if (bits[index % 400, index / 400]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                        setPixels(pixels, 0, 400, 0, 0, 400, 400)
                    }
                }
                ensureActive(); qrcode.value = image
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { qrcode.value = null }
            finally { if (isActive) state.value = true }
        }
    }
    fun clearQrCode() { qrJob?.cancel(); qrJob = null; qrcode.value = null; state.value = false }
    override fun onCleared() { clearQrCode() }
}
