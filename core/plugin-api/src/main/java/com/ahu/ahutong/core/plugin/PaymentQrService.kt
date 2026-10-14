package com.ahu.ahutong.core.plugin

import kotlinx.coroutines.flow.StateFlow

/** Optional campus-card capability. Predictive callers never receive the QR content. */
interface PaymentQrService {
    val visible: StateFlow<Boolean>
    fun requestOpen()
    suspend fun prefetch(): Result<Unit>
    suspend fun clearSensitive()
}
