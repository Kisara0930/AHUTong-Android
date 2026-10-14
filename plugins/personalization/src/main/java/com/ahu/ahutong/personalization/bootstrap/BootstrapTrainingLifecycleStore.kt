package com.ahu.ahutong.personalization.bootstrap

import android.content.Context
import com.ahu.ahutong.personalization.storage.BootstrapTrainingConsentEntity
import com.ahu.ahutong.personalization.storage.BootstrapTrainingDeletionTombstoneEntity
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Version-stable consent and deletion outbox kept outside the Room learning database.
 *
 * The learning database may be rebuilt after corruption or by an older application version.
 * Keeping the encrypted revocation capability here ensures that such recovery never destroys
 * the application's ability to delete data already accepted by the server.
 */
class BootstrapTrainingLifecycleStore internal constructor(
    context: Context,
    storeName: String
) {
    constructor(context: Context) : this(context, STORE_NAME)

    private val preferences = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)

    @Synchronized
    fun persistActive(consent: BootstrapTrainingConsentEntity) {
        val records = readRecords().toMutableList()
        val existing = records.indexOfFirst { it.participantId == consent.participantId }
        val value = LifecycleRecord(
            profileKey = consent.profileKey,
            participantId = consent.participantId,
            consentLifecycleId = consent.consentLifecycleId,
            secretAlias = consent.secretAlias,
            encryptedRevocationCapability = consent.encryptedRevocationCapability,
            state = STATE_ACTIVE,
            deletionId = null,
            attemptCount = 0,
            nextAttemptAtEpochMs = Long.MAX_VALUE,
            lastErrorCode = null,
            createdAtEpochMs = consent.createdAtEpochMs
        )
        if (existing >= 0) {
            check(records[existing].state == STATE_ACTIVE) {
                "cannot reactivate a lifecycle that is pending deletion"
            }
            records[existing] = value
        } else {
            records += value
        }
        writeRecords(records)
    }

    @Synchronized
    fun markDeletion(consent: BootstrapTrainingConsentEntity, nowEpochMs: Long): LifecycleRecord {
        val records = readRecords().toMutableList()
        val existingIndex = records.indexOfFirst { it.participantId == consent.participantId }
        val existing = records.getOrNull(existingIndex)
        val value = LifecycleRecord(
            profileKey = consent.profileKey,
            participantId = consent.participantId,
            consentLifecycleId = consent.consentLifecycleId,
            secretAlias = consent.secretAlias,
            encryptedRevocationCapability = consent.encryptedRevocationCapability,
            state = STATE_DELETE_PENDING,
            deletionId = existing?.deletionId ?: UUID.randomUUID().toString(),
            attemptCount = existing?.attemptCount ?: 0,
            nextAttemptAtEpochMs = minOf(existing?.nextAttemptAtEpochMs ?: nowEpochMs, nowEpochMs),
            lastErrorCode = existing?.lastErrorCode,
            createdAtEpochMs = existing?.createdAtEpochMs ?: consent.createdAtEpochMs
        )
        if (existingIndex >= 0) records[existingIndex] = value else records += value
        writeRecords(records)
        return value
    }

    @Synchronized
    fun importLegacy(value: BootstrapTrainingDeletionTombstoneEntity) {
        val records = readRecords().toMutableList()
        if (records.any { it.participantId == value.participantId && it.state == STATE_DELETE_PENDING }) return
        records += LifecycleRecord(
            profileKey = null,
            participantId = value.participantId,
            consentLifecycleId = value.consentLifecycleId,
            secretAlias = value.secretAlias,
            encryptedRevocationCapability = value.encryptedRevocationCapability,
            state = STATE_DELETE_PENDING,
            deletionId = value.deletionId,
            attemptCount = value.attemptCount,
            nextAttemptAtEpochMs = value.nextAttemptAtEpochMs,
            lastErrorCode = value.lastErrorCode,
            createdAtEpochMs = value.createdAtEpochMs
        )
        writeRecords(records)
    }

    /** Converts externally preserved ACTIVE lifecycles into deletions if Room lost them. */
    @Synchronized
    fun reconcileRoomLifecycles(roomConsentLifecycleIds: Set<String>, nowEpochMs: Long): Boolean {
        val records = readRecords().toMutableList()
        var changed = false
        records.indices.forEach { index ->
            val record = records[index]
            if (
                record.state == STATE_ACTIVE && record.consentLifecycleId !in roomConsentLifecycleIds
            ) {
                records[index] = record.copy(
                    state = STATE_DELETE_PENDING,
                    deletionId = record.deletionId ?: UUID.randomUUID().toString(),
                    nextAttemptAtEpochMs = nowEpochMs,
                    lastErrorCode = "ROOM_LIFECYCLE_MISSING"
                )
                changed = true
            }
        }
        if (changed) writeRecords(records)
        return changed
    }

    @Synchronized
    fun dueDeletion(nowEpochMs: Long): LifecycleRecord? = readRecords()
        .asSequence()
        .filter { it.state == STATE_DELETE_PENDING && it.nextAttemptAtEpochMs <= nowEpochMs }
        .minByOrNull(LifecycleRecord::createdAtEpochMs)

    @Synchronized
    fun retryDeletion(
        participantId: String,
        nextAttemptAtEpochMs: Long,
        errorCode: String
    ) {
        update(participantId) { value ->
            value.copy(
                attemptCount = value.attemptCount + 1,
                nextAttemptAtEpochMs = nextAttemptAtEpochMs,
                lastErrorCode = errorCode
            )
        }
    }

    @Synchronized
    fun acknowledgeDeletion(participantId: String) {
        writeRecords(readRecords().filterNot { it.participantId == participantId })
    }

    @Synchronized
    fun pendingDeletionCount(): Int = readRecords().count { it.state == STATE_DELETE_PENDING }

    @Synchronized
    fun nextDeletionAttemptAtEpochMs(): Long? = readRecords()
        .filter { it.state == STATE_DELETE_PENDING }
        .minOfOrNull(LifecycleRecord::nextAttemptAtEpochMs)

    @Synchronized
    fun hasActiveLifecycle(): Boolean = readRecords().any { it.state == STATE_ACTIVE }

    private fun update(participantId: String, transform: (LifecycleRecord) -> LifecycleRecord) {
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.participantId == participantId }
        if (index < 0) return
        records[index] = transform(records[index])
        writeRecords(records)
    }

    private fun readRecords(): List<LifecycleRecord> {
        val records = preferences.all.entries
            .asSequence()
            .filter { it.key.startsWith(RECORD_KEY_PREFIX) }
            .mapNotNull { (_, value) -> parseRecord(value) }
            .toMutableList()
        val legacy = preferences.getString(LEGACY_RECORDS_KEY, null)
        if (legacy != null) {
            parseLegacyRecords(legacy)?.forEach { value ->
                if (records.none { existing -> existing.participantId == value.participantId }) records += value
            }
        }
        return records
    }

    private fun writeRecords(records: List<LifecycleRecord>) {
        val expectedKeys = records.mapTo(mutableSetOf()) { RECORD_KEY_PREFIX + it.participantId }
        val editor = preferences.edit()
        val legacy = preferences.getString(LEGACY_RECORDS_KEY, null)
        if (legacy == null || parseLegacyRecords(legacy) != null) editor.remove(LEGACY_RECORDS_KEY)
        preferences.all.entries
            .filter { (key, value) ->
                key.startsWith(RECORD_KEY_PREFIX) && key !in expectedKeys && parseRecord(value) != null
            }
            .forEach { editor.remove(it.key) }
        records.forEach { editor.putString(RECORD_KEY_PREFIX + it.participantId, it.toJson().toString()) }
        check(editor.commit()) {
            "failed to persist bootstrap training lifecycle outbox"
        }
    }

    private fun parseRecord(value: Any?): LifecycleRecord? = (value as? String)?.let { raw ->
        runCatching { LifecycleRecord.fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun parseLegacyRecords(raw: String): List<LifecycleRecord>? = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index -> LifecycleRecord.fromJson(array.getJSONObject(index)) }
    }.getOrNull()

    data class LifecycleRecord(
        val profileKey: String?,
        val participantId: String,
        val consentLifecycleId: String,
        val secretAlias: String,
        val encryptedRevocationCapability: String,
        val state: String,
        val deletionId: String?,
        val attemptCount: Int,
        val nextAttemptAtEpochMs: Long,
        val lastErrorCode: String?,
        val createdAtEpochMs: Long
    ) {
        fun toJson() = JSONObject()
            .put("profileKey", profileKey)
            .put("participantId", participantId)
            .put("consentLifecycleId", consentLifecycleId)
            .put("secretAlias", secretAlias)
            .put("encryptedRevocationCapability", encryptedRevocationCapability)
            .put("state", state)
            .put("deletionId", deletionId)
            .put("attemptCount", attemptCount)
            .put("nextAttemptAtEpochMs", nextAttemptAtEpochMs)
            .put("lastErrorCode", lastErrorCode)
            .put("createdAtEpochMs", createdAtEpochMs)

        companion object {
            fun fromJson(value: JSONObject) = LifecycleRecord(
                profileKey = value.optString("profileKey").takeIf(String::isNotBlank),
                participantId = value.getString("participantId"),
                consentLifecycleId = value.getString("consentLifecycleId"),
                secretAlias = value.getString("secretAlias"),
                encryptedRevocationCapability = value.getString("encryptedRevocationCapability"),
                state = value.getString("state"),
                deletionId = value.optString("deletionId").takeIf(String::isNotBlank),
                attemptCount = value.optInt("attemptCount", 0),
                nextAttemptAtEpochMs = value.optLong("nextAttemptAtEpochMs", Long.MAX_VALUE),
                lastErrorCode = value.optString("lastErrorCode").takeIf(String::isNotBlank),
                createdAtEpochMs = value.optLong("createdAtEpochMs", 0L)
            )
        }
    }

    private companion object {
        const val STORE_NAME = "bootstrap_training_lifecycle_outbox_v1"
        const val LEGACY_RECORDS_KEY = "records"
        const val RECORD_KEY_PREFIX = "lifecycle."
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_DELETE_PENDING = "DELETE_PENDING"
    }
}
