package com.ahu.ahutong.personalization.telemetry

import com.ahu.ahutong.personalization.storage.BehaviorDao
import com.ahu.ahutong.personalization.storage.TelemetryReportEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class TelemetryUploader constructor(
    private val dao: BehaviorDao,
    private val secretStore: TelemetrySecretStore
) {
    private val random = SecureRandom()

    /** Returns true while durable work remains and WorkManager should keep retrying. */
    suspend fun uploadDue(): Boolean {
        uploadDeletions()
        // Revocation removes every report for that consent lifecycle. Remaining READY reports
        // therefore belong to profiles whose independent consent is still active.
        uploadReports()
        return dao.totalReadyTelemetryReportCount() > 0 || dao.pendingDeletionTombstoneCount() > 0
    }

    private suspend fun uploadReports() {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        dao.deleteExpiredTelemetryReports(now)
        if (dao.lastTelemetryUploadAttemptEpochDay() == today) return
        dao.dueTelemetryReports(now, today, MAX_REPORTS_PER_RUN).forEach { report ->
            val lifecycle = dao.telemetryState(report.profileKey)
            if (lifecycle == null || lifecycle.lifecycleState != "ACTIVE" ||
                lifecycle.consentLifecycleId != report.consentLifecycleId ||
                lifecycle.telemetryId != report.telemetryId
            ) {
                update(report, "QUARANTINED", report.attemptCount, Long.MAX_VALUE, today)
                return@forEach
            }
            if (sha256(report.payloadJson) != report.payloadSha256Hex ||
                sha256(report.exactRequestBodyJson) != report.bodySha256Hex
            ) {
                update(report, "QUARANTINED", report.attemptCount, Long.MAX_VALUE, today)
                return@forEach
            }

            val attempts = report.attemptCount + 1
            val retryAt = now + backoffMillis(attempts)
            update(report, "READY", attempts, retryAt, today)
            val credentialResponse = runCatching {
                TelemetryApi.API.credential(
                    TelemetryCredentialRequest(
                        schemaVersion = report.schemaVersion,
                        batchId = report.batchId,
                        bodySha256Hex = report.bodySha256Hex,
                        appVersionCode = com.ahu.ahutong.plugin.personalization.HostAppVersion.VERSION_CODE
                    )
                )
            }.getOrNull()
            if (credentialResponse?.code() in PERMANENT_FAILURE_CODES) {
                update(report, "QUARANTINED", attempts, Long.MAX_VALUE, today)
                return@forEach
            }
            val credential = credentialResponse?.takeIf { it.isSuccessful }?.body()
            if (credential == null || credential.expiresAtEpochMs <= now) return@forEach

            val response = runCatching {
                TelemetryApi.API.upload(
                    telemetryCredential = "Telemetry ${credential.credential}",
                    bodySha256Hex = report.bodySha256Hex,
                    exactBody = report.exactRequestBodyJson.toRequestBody(JSON_MEDIA_TYPE)
                )
            }.getOrNull()
            val code = response?.code()
            response?.body()?.close()
            response?.errorBody()?.close()
            when {
                response?.isSuccessful == true || code == 409 ->
                    update(report, "ACKED", attempts, Long.MAX_VALUE, today)
                code in PERMANENT_FAILURE_CODES ->
                    update(report, "QUARANTINED", attempts, Long.MAX_VALUE, today)
                else -> Unit // READY and its durable next-attempt time were written before network I/O.
            }
        }
    }

    private suspend fun uploadDeletions() {
        val now = System.currentTimeMillis()
        dao.expiredDeletionTombstones(now).forEach { expired ->
            dao.deleteDeletionTombstone(expired.deletionId)
            secretStore.delete(expired.revocationKeyAlias)
        }
        dao.pendingDeletionTombstones(now, MAX_REPORTS_PER_RUN).forEach { value ->
            val attempts = value.attemptCount + 1
            dao.retryDeletionTombstone(value.deletionId, attempts, now + backoffMillis(attempts))
            val capability = runCatching {
                secretStore.decrypt(value.revocationKeyAlias, value.encryptedRevocationCapability)
            }.getOrNull() ?: return@forEach
            val response = runCatching {
                TelemetryApi.API.delete(
                    TelemetryDeletionRequest(
                        deletionId = value.deletionId,
                        telemetryId = value.telemetryId,
                        modelGenerationId = value.modelGenerationId,
                        revocationCapability = capability
                    )
                )
            }.getOrNull()
            val code = response?.code()
            response?.body()?.close()
            response?.errorBody()?.close()
            if (response?.isSuccessful == true || code == 404 || code == 409) {
                dao.deleteDeletionTombstone(value.deletionId)
                secretStore.delete(value.revocationKeyAlias)
            }
        }
    }

    private suspend fun update(
        report: TelemetryReportEntity,
        state: String,
        attempts: Int,
        nextAttempt: Long,
        today: Long
    ) = dao.updateTelemetryReport(report.reportId, state, attempts, nextAttempt, today)

    private fun backoffMillis(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, BACKOFF_DAYS.lastIndex)
        return BACKOFF_DAYS[index] * MILLIS_PER_DAY + random.nextInt(MAX_JITTER_MS.toInt() + 1)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val PERMANENT_FAILURE_CODES = setOf(400, 413, 422)
        val BACKOFF_DAYS = longArrayOf(1, 2, 4, 7)
        const val MILLIS_PER_DAY = 86_400_000L
        const val MAX_JITTER_MS = 6 * 60 * 60_000L
        const val MAX_REPORTS_PER_RUN = 1
    }
}
