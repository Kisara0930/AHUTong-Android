package com.ahu.ahutong.personalization.telemetry

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.storage.BehaviorDao
import com.ahu.ahutong.personalization.storage.ShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.CandidateShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.JourneyShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.PresetShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.TelemetryAggregateWindowEntity
import com.ahu.ahutong.personalization.storage.TelemetryStateEntity
import com.ahu.ahutong.personalization.ui.SuggestionDeliveryLane
import com.google.gson.Gson
import java.util.UUID

data class StoredActionMetric(
    val actionId: String,
    val eligibleSampleCount: Int,
    val pairedSampleCount: Int,
    val statTop1Correct: Long,
    val statTop3Hit: Long,
    val statReciprocalRankSum: Double,
    val statBrierSum: Double,
    val statLogLossSum: Double,
    val tinyTop1Correct: Long,
    val tinyTop3Hit: Long,
    val tinyReciprocalRankSum: Double,
    val tinyBrierSum: Double,
    val tinyLogLossSum: Double,
    val tinyWins: Int,
    val statWins: Int,
    val ties: Int
)

data class StoredPerActionMetrics(val values: List<StoredActionMetric> = emptyList())

internal fun sanitizeStoredActionMetrics(values: List<*>): List<StoredActionMetric> =
    values.mapNotNull { value ->
        (value as? StoredActionMetric)?.takeIf { it.isValid() }
    }

private fun StoredActionMetric.isValid(): Boolean =
    actionId in AppActionCatalog.outputIndex &&
        actionId != AppActionCatalog.NONE_OUTPUT_ID &&
        actionId != AppActionCatalog.OTHER_OUTPUT_ID &&
        eligibleSampleCount >= 0 && pairedSampleCount in 0..eligibleSampleCount &&
        statTop1Correct in 0..pairedSampleCount.toLong() &&
        statTop3Hit in statTop1Correct..pairedSampleCount.toLong() &&
        tinyTop1Correct in 0..pairedSampleCount.toLong() &&
        tinyTop3Hit in tinyTop1Correct..pairedSampleCount.toLong() &&
        statReciprocalRankSum.isFinite() && statBrierSum.isFinite() && statLogLossSum.isFinite() &&
        tinyReciprocalRankSum.isFinite() && tinyBrierSum.isFinite() && tinyLogLossSum.isFinite() &&
        tinyWins >= 0 && statWins >= 0 && ties >= 0 &&
        tinyWins + statWins + ties == pairedSampleCount

/**
 * The only component allowed to turn a per-decision evaluation into telemetry state.
 * It is invoked in the same Room transaction as label resolution and stores sums only;
 * report construction never reads behavior, feature, probability, replay, or model tables.
 */
class TelemetryAggregateStore constructor(
    private val dao: BehaviorDao,
    private val v3Store: TelemetryV3AggregateStore
) {
    private val gson = Gson()

    suspend fun contribute(evaluation: ShadowEvaluationEntity) {
        if (!evaluation.telemetryEligible) return
        val lifecycle = dao.telemetryState(evaluation.profileKey)
            ?.takeIf { it.lifecycleState == "ACTIVE" }
            ?: return
        var open = dao.openTelemetryAggregateWindow(evaluation.profileKey, lifecycle.consentLifecycleId)
        if (open != null && !sameBinding(open, lifecycle, evaluation)) {
            dao.transitionTelemetryAggregateWindow(
                open.windowId,
                expectedState = "OPEN",
                state = "SUPPRESSED",
                updatedAtEpochMs = System.currentTimeMillis()
            )
            open = null
        }
        val current = open ?: emptyWindow(lifecycle, evaluation).also { created ->
            dao.insertTelemetryAggregateWindow(created)
        }
        val perAction = readPerAction(current.perActionJson).associateBy(StoredActionMetric::actionId).toMutableMap()
        if (evaluation.trueLabel != AppActionCatalog.NONE_OUTPUT_ID &&
            evaluation.trueLabel != AppActionCatalog.OTHER_OUTPUT_ID &&
            evaluation.trueLabel in AppActionCatalog.outputIndex
        ) {
            val old = perAction[evaluation.trueLabel] ?: emptyAction(evaluation.trueLabel)
            perAction[evaluation.trueLabel] = old.add(evaluation)
        }
        val paired = evaluation.paired
        val eligibleCount = current.eligibleSampleCount + 1
        val now = System.currentTimeMillis()
        dao.updateTelemetryAggregateWindow(
            current.copy(
                endEvaluationSeq = evaluation.evaluationSeq,
                windowEndEpochDay = evaluation.occurredEpochDay,
                eligibleSampleCount = eligibleCount,
                organicNonNoneSampleCount = current.organicNonNoneSampleCount + if (evaluation.isOrganicNonNone) 1 else 0,
                pairedSampleCount = current.pairedSampleCount + if (paired) 1 else 0,
                statTop1Correct = current.statTop1Correct + if (paired) evaluation.statTop1 else 0,
                statTop3Hit = current.statTop3Hit + if (paired) evaluation.statTop3 else 0,
                statReciprocalRankSum = current.statReciprocalRankSum + if (paired) evaluation.statReciprocalRank else 0.0,
                statBrierSum = current.statBrierSum + if (paired) evaluation.statBrier else 0.0,
                statLogLossSum = current.statLogLossSum + if (paired) evaluation.statLogLoss else 0.0,
                tinyTop1Correct = current.tinyTop1Correct + if (paired) evaluation.tinyTop1 else 0,
                tinyTop3Hit = current.tinyTop3Hit + if (paired) evaluation.tinyTop3 else 0,
                tinyReciprocalRankSum = current.tinyReciprocalRankSum + if (paired) evaluation.tinyReciprocalRank else 0.0,
                tinyBrierSum = current.tinyBrierSum + if (paired) evaluation.tinyBrier else 0.0,
                tinyLogLossSum = current.tinyLogLossSum + if (paired) evaluation.tinyLogLoss else 0.0,
                tinyWins = current.tinyWins + if (paired && evaluation.winner == "TINY") 1 else 0,
                statWins = current.statWins + if (paired && evaluation.winner == "STAT") 1 else 0,
                ties = current.ties + if (paired && evaluation.winner == "TIE") 1 else 0,
                statInferenceNanosSum = current.statInferenceNanosSum + evaluation.statInferenceNanos.coerceAtLeast(0),
                tinyInferenceNanosSum = current.tinyInferenceNanosSum + evaluation.tinyInferenceNanos.coerceAtLeast(0),
                trainingNanosSum = current.trainingNanosSum + evaluation.trainingNanos.coerceAtLeast(0),
                modelSizeBytesMax = maxOf(current.modelSizeBytesMax, evaluation.modelSizeBytes),
                perActionJson = gson.toJson(StoredPerActionMetrics(perAction.values.sortedBy(StoredActionMetric::actionId))),
                state = if (eligibleCount >= WINDOW_SAMPLE_COUNT) "CLOSED" else "OPEN",
                updatedAtEpochMs = now
            )
        )
        v3Store.contributeNextAction(evaluation)
    }

    suspend fun contributeJourney(evaluation: JourneyShadowEvaluationEntity) =
        v3Store.contributeJourney(evaluation)

    suspend fun contributePreset(evaluation: PresetShadowEvaluationEntity) =
        v3Store.contributePreset(evaluation)

    suspend fun contributeCandidate(evaluation: CandidateShadowEvaluationEntity) =
        v3Store.contributeCandidate(evaluation)

    suspend fun recordPresetInteraction(
        profileKey: String,
        event: TelemetryPresetEvent,
        feedbackWeight: Double? = null
    ) = v3Store.recordPresetInteraction(profileKey, event, feedbackWeight)

    suspend fun recordDelivery(
        profileKey: String,
        lane: SuggestionDeliveryLane,
        event: TelemetryDeliveryEvent,
        blockReason: String? = null,
        assistedRewardWeight: Double? = null,
        latencyMs: Long? = null
    ) = v3Store.recordDelivery(
        profileKey,
        lane,
        event,
        blockReason,
        assistedRewardWeight,
        latencyMs
    )

    fun readPerAction(json: String): List<StoredActionMetric> = runCatching {
        val values: List<*> = gson.fromJson(json, StoredPerActionMetrics::class.java)
            ?.values
            .orEmpty()
        sanitizeStoredActionMetrics(values)
    }.getOrDefault(emptyList())

    fun readV3Aggregate(json: String): StoredTelemetryV3Aggregate = v3Store.decodeAggregate(json)

    fun readV3Aggregate(json: String, task: TelemetryV3Task): StoredTelemetryV3Aggregate? =
        v3Store.decodeAggregate(json, task)

    private fun emptyWindow(
        lifecycle: TelemetryStateEntity,
        evaluation: ShadowEvaluationEntity
    ): TelemetryAggregateWindowEntity {
        val now = System.currentTimeMillis()
        return TelemetryAggregateWindowEntity(
            windowId = UUID.randomUUID().toString(),
            profileKey = evaluation.profileKey,
            consentLifecycleId = lifecycle.consentLifecycleId,
            telemetryId = lifecycle.telemetryId,
            modelGenerationId = lifecycle.modelGenerationId,
            activeCheckpointId = evaluation.activeCheckpointId,
            startEvaluationSeq = evaluation.evaluationSeq,
            endEvaluationSeq = evaluation.evaluationSeq,
            windowStartEpochDay = evaluation.occurredEpochDay,
            windowEndEpochDay = evaluation.occurredEpochDay,
            eligibleSampleCount = 0,
            organicNonNoneSampleCount = 0,
            pairedSampleCount = 0,
            statTop1Correct = 0,
            statTop3Hit = 0,
            statReciprocalRankSum = 0.0,
            statBrierSum = 0.0,
            statLogLossSum = 0.0,
            tinyTop1Correct = 0,
            tinyTop3Hit = 0,
            tinyReciprocalRankSum = 0.0,
            tinyBrierSum = 0.0,
            tinyLogLossSum = 0.0,
            tinyWins = 0,
            statWins = 0,
            ties = 0,
            statInferenceNanosSum = 0,
            tinyInferenceNanosSum = 0,
            trainingNanosSum = 0,
            modelSizeBytesMax = 0,
            perActionJson = gson.toJson(StoredPerActionMetrics()),
            appVersionCode = evaluation.appVersionCode,
            statModelVersion = evaluation.statModelVersion,
            tinyModelVersion = evaluation.tinyModelVersion,
            featureSchemaVersion = evaluation.featureSchemaVersion,
            outputSchemaVersion = evaluation.outputSchemaVersion,
            actionCatalogVersion = evaluation.actionCatalogVersion,
            trainingConfigVersion = evaluation.trainingConfigVersion,
            metricSchemaVersion = evaluation.metricSchemaVersion,
            state = "OPEN",
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
    }

    private fun sameBinding(
        window: TelemetryAggregateWindowEntity,
        lifecycle: TelemetryStateEntity,
        evaluation: ShadowEvaluationEntity
    ): Boolean = window.telemetryId == lifecycle.telemetryId &&
        window.modelGenerationId == lifecycle.modelGenerationId &&
        window.activeCheckpointId == evaluation.activeCheckpointId &&
        window.appVersionCode == evaluation.appVersionCode &&
        window.statModelVersion == evaluation.statModelVersion &&
        window.tinyModelVersion == evaluation.tinyModelVersion &&
        window.featureSchemaVersion == evaluation.featureSchemaVersion &&
        window.outputSchemaVersion == evaluation.outputSchemaVersion &&
        window.actionCatalogVersion == evaluation.actionCatalogVersion &&
        window.trainingConfigVersion == evaluation.trainingConfigVersion &&
        window.metricSchemaVersion == evaluation.metricSchemaVersion

    private fun emptyAction(actionId: String) = StoredActionMetric(
        actionId, 0, 0, 0, 0, 0.0, 0.0, 0.0,
        0, 0, 0.0, 0.0, 0.0, 0, 0, 0
    )

    private fun StoredActionMetric.add(value: ShadowEvaluationEntity): StoredActionMetric {
        val paired = value.paired
        return copy(
            eligibleSampleCount = eligibleSampleCount + 1,
            pairedSampleCount = pairedSampleCount + if (paired) 1 else 0,
            statTop1Correct = statTop1Correct + if (paired) value.statTop1 else 0,
            statTop3Hit = statTop3Hit + if (paired) value.statTop3 else 0,
            statReciprocalRankSum = statReciprocalRankSum + if (paired) value.statReciprocalRank else 0.0,
            statBrierSum = statBrierSum + if (paired) value.statBrier else 0.0,
            statLogLossSum = statLogLossSum + if (paired) value.statLogLoss else 0.0,
            tinyTop1Correct = tinyTop1Correct + if (paired) value.tinyTop1 else 0,
            tinyTop3Hit = tinyTop3Hit + if (paired) value.tinyTop3 else 0,
            tinyReciprocalRankSum = tinyReciprocalRankSum + if (paired) value.tinyReciprocalRank else 0.0,
            tinyBrierSum = tinyBrierSum + if (paired) value.tinyBrier else 0.0,
            tinyLogLossSum = tinyLogLossSum + if (paired) value.tinyLogLoss else 0.0,
            tinyWins = tinyWins + if (paired && value.winner == "TINY") 1 else 0,
            statWins = statWins + if (paired && value.winner == "STAT") 1 else 0,
            ties = ties + if (paired && value.winner == "TIE") 1 else 0
        )
    }

    private companion object {
        const val WINDOW_SAMPLE_COUNT = 64
    }
}
