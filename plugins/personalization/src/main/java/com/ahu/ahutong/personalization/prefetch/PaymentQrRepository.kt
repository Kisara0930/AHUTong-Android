package com.ahu.ahutong.personalization.prefetch

import com.ahu.ahutong.personalization.runtime.PersonalizationFeatureAccess

/** QR payloads and cache ownership stay in the optional campus-card plugin. */
class PaymentQrRepository(private val features: PersonalizationFeatureAccess) {
    suspend fun activateProfile(profileKey: String, profileGeneration: Long, loginGeneration: Long) = features.clearPreparedPaymentQr()
    suspend fun clearSensitive() = features.clearPreparedPaymentQr()
    // Preserve the existing fail-closed policy until the upstream QR protocol has
    // a verified server expiry. Moving components must not enable sensitive I/O.
    suspend fun prefetchPredictively(): Result<Unit> = Result.failure(
        IllegalStateException("payment QR endpoint expiry semantics have not been protocol-verified")
    )
}
