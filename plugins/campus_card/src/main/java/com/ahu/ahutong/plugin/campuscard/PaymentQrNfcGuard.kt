package com.ahu.ahutong.plugin.campuscard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal const val PAYMENT_QR_NFC_READER_FLAGS =
    NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NFC_BARCODE or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

internal fun shouldEnablePaymentQrNfcGuard(
    isHomeActive: Boolean,
    isPaymentQrVisible: Boolean,
    isActivityResumed: Boolean,
    isNfcEnabled: Boolean
): Boolean = isHomeActive && isPaymentQrVisible && isActivityResumed && isNfcEnabled

/**
 * Keeps NFC in reader-only mode while the payment QR is visible on the active home page.
 * Every discovered tag is deliberately discarded; reader mode also disables card emulation.
 */
@Composable
internal fun PaymentQrNfcGuard(
    isHomeActive: Boolean,
    isPaymentQrVisible: Boolean
) {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val adapter = remember(activity) {
        activity?.let(NfcAdapter::getDefaultAdapter)
    }

    DisposableEffect(activity, adapter, lifecycleOwner, isHomeActive, isPaymentQrVisible) {
        if (activity == null || adapter == null || !isHomeActive || !isPaymentQrVisible) {
            return@DisposableEffect onDispose {}
        }

        var readerModeEnabled = false

        fun disableReaderMode() {
            if (!readerModeEnabled) return
            runCatching { adapter.disableReaderMode(activity) }
            readerModeEnabled = false
        }

        fun synchronizeReaderMode() {
            val shouldEnable = shouldEnablePaymentQrNfcGuard(
                isHomeActive = isHomeActive,
                isPaymentQrVisible = isPaymentQrVisible,
                isActivityResumed = lifecycleOwner.lifecycle.currentState
                    .isAtLeast(Lifecycle.State.RESUMED),
                isNfcEnabled = runCatching { adapter.isEnabled }.getOrDefault(false)
            )
            if (!shouldEnable) {
                disableReaderMode()
            } else if (!readerModeEnabled) {
                runCatching {
                    adapter.enableReaderMode(
                        activity,
                        NfcAdapter.ReaderCallback { },
                        PAYMENT_QR_NFC_READER_FLAGS,
                        null
                    )
                }.onSuccess {
                    readerModeEnabled = true
                }
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_PAUSE -> synchronizeReaderMode()
                else -> Unit
            }
        }
        val adapterStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    synchronizeReaderMode()
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        val receiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                activity,
                adapterStateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.isSuccess
        synchronizeReaderMode()

        onDispose {
            disableReaderMode()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            if (receiverRegistered) {
                runCatching { activity.unregisterReceiver(adapterStateReceiver) }
            }
        }
    }
}
