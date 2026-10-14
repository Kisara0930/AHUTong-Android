package com.ahu.ahutong.personalization.telemetry

import com.ahu.ahutong.plugin.personalization.HostAppVersion as BuildConfig
import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.storage.BehaviorDao
import com.ahu.ahutong.personalization.storage.CandidateShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.JourneyShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.PresetShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.ShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.TaskModelStateEntity
import com.ahu.ahutong.personalization.storage.TelemetryStateEntity
import com.ahu.ahutong.personalization.storage.TelemetryV3AggregateWindowEntity
import com.ahu.ahutong.personalization.ui.SuggestionDeliveryLane
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TelemetryDeliveryEvent {
    OPPORTUNITY,
    MODEL_GATE_PASSED,
    ENTERED_VISIBLE_SURFACE,
    CLICKED,
    COMPLETED,
    DISMISSED,
    TIMED_OUT,
    BLOCKED,
    ASSISTED_REWARD
}

enum class TelemetryPresetEvent {
    EXPOSED,
    APPLIED,
    QUERY_CONFIRMED,
    REPLACED,
    REMOVED,
    EXPIRED_WITHOUT_LABEL
}

/**
 * Converts later personalization tasks into privacy-bounded aggregate state at event time.
 * No behavior route, semantic value, candidate id, parameter payload, probability vector,
 * checkpoint id, or per-decision identifier is written to the upload window.
 */
class TelemetryV3AggregateStore constructor(
    private val dao: BehaviorDao
) {
    private val gson = Gson()
    private val aggregateCodec = TelemetryV3AggregateCodec(gson)
    private val taskLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun contributeNextAction(value: ShadowEvaluationEntity) {
        if (!value.telemetryEligible) return
        mutate(
            profileKey = value.profileKey,
            task = TelemetryV3Task.NEXT_ACTION,
            occurredEpochDay = value.occurredEpochDay,
            naturalHoldout = value.promotionEligible,
            featureSchemaVersion = value.featureSchemaVersion,
            outputSchemaVersion = value.outputSchemaVersion
        ) { stored ->
            val old = stored.classification ?: V3ClassificationAggregate()
            stored.copy(
                classification = old.copy(
                    nonNoneSampleCount = old.nonNoneSampleCount + if (value.isOrganicNonNone) 1 else 0,
                    statistical = old.statistical.add(
                        value.statTop1, value.statTop3, value.statReciprocalRank,
                        value.statBrier, value.statLogLoss, value.statTop1Confidence
                    ),
                    tinyMlp = if (value.paired) old.tinyMlp.add(
                        value.tinyTop1, value.tinyTop3, value.tinyReciprocalRank,
                        value.tinyBrier, value.tinyLogLoss, value.tinyTop1Confidence
                    ) else old.tinyMlp,
                    effective = old.effective.add(
                        value.effectiveTop1, value.effectiveTop3, value.effectiveReciprocalRank,
                        value.effectiveBrier, value.effectiveLogLoss, value.effectiveTop1Confidence
                    ),
                    recentBaseline = old.recentBaseline.add(
                        value.recentTop1, value.recentTop3, value.recentReciprocalRank,
                        value.recentBrier, value.recentLogLoss, value.recentTop1Confidence
                    ),
                    timeBaseline = old.timeBaseline.add(
                        value.timeTop1, value.timeTop3, value.timeReciprocalRank,
                        value.timeBrier, value.timeLogLoss, value.timeTop1Confidence
                    ),
                    tinyVsStat = old.tinyVsStat.addWinner(
                        when (value.winner) {
                            "TINY" -> Winner.FIRST
                            "STAT" -> Winner.SECOND
                            "TIE" -> Winner.TIE
                            else -> Winner.NONE
                        }
                    ),
                    promotionHoldout = if (value.promotionEligible) old.promotionHoldout.add(
                        stat = MetricInput(
                            value.statTop1, value.statTop3, value.statReciprocalRank,
                            value.statBrier, value.statLogLoss, value.statTop1Confidence
                        ),
                        tiny = if (value.paired) MetricInput(
                            value.tinyTop1, value.tinyTop3, value.tinyReciprocalRank,
                            value.tinyBrier, value.tinyLogLoss, value.tinyTop1Confidence
                        ) else null,
                        effective = MetricInput(
                            value.effectiveTop1, value.effectiveTop3, value.effectiveReciprocalRank,
                            value.effectiveBrier, value.effectiveLogLoss, value.effectiveTop1Confidence
                        ),
                        winner = when (value.winner) {
                            "TINY" -> Winner.FIRST
                            "STAT" -> Winner.SECOND
                            "TIE" -> Winner.TIE
                            else -> Winner.NONE
                        }
                    ) else old.promotionHoldout,
                    stageCounts = old.stageCounts.increment(normalizeStage(value.stage)),
                    tierCounts = old.tierCounts.increment(normalizeTier(value.tier)),
                    statInferenceNanosSum = old.statInferenceNanosSum + value.statInferenceNanos.coerceAtLeast(0),
                    tinyInferenceNanosSum = old.tinyInferenceNanosSum + value.tinyInferenceNanos.coerceAtLeast(0),
                    trainingNanosSum = old.trainingNanosSum + value.trainingNanos.coerceAtLeast(0),
                    modelSizeBytesMax = maxOf(old.modelSizeBytesMax, value.modelSizeBytes.coerceAtLeast(0))
                )
            )
        }
    }

    suspend fun contributeJourney(value: JourneyShadowEvaluationEntity) {
        mutate(
            profileKey = value.profileKey,
            task = TelemetryV3Task.JOURNEY_GOAL,
            occurredEpochDay = value.occurredEpochDay,
            naturalHoldout = value.promotionEligible,
            featureSchemaVersion = value.featureSchemaVersion,
            outputSchemaVersion = value.journeyOutputSchemaVersion
        ) { stored ->
            val old = stored.classification ?: V3ClassificationAggregate()
            val winner = when {
                !value.tinyAvailable -> Winner.NONE
                value.tinyLogLoss + WIN_EPSILON < value.statLogLoss -> Winner.FIRST
                value.statLogLoss + WIN_EPSILON < value.tinyLogLoss -> Winner.SECOND
                else -> Winner.TIE
            }
            stored.copy(
                classification = old.copy(
                    nonNoneSampleCount = old.nonNoneSampleCount + 1,
                    statistical = old.statistical.add(
                        value.statTop1, value.statTop3, value.statReciprocalRank,
                        value.statBrier, value.statLogLoss, value.statTop1Confidence
                    ),
                    tinyMlp = if (value.tinyAvailable) old.tinyMlp.add(
                        value.tinyTop1, value.tinyTop3, value.tinyReciprocalRank,
                        value.tinyBrier, value.tinyLogLoss, value.tinyTop1Confidence
                    ) else old.tinyMlp,
                    effective = old.effective.add(
                        value.effectiveTop1, value.effectiveTop3, value.effectiveReciprocalRank,
                        value.effectiveBrier, value.effectiveLogLoss, value.effectiveTop1Confidence
                    ),
                    tinyVsStat = old.tinyVsStat.addWinner(winner),
                    promotionHoldout = if (value.promotionEligible) old.promotionHoldout.add(
                        stat = MetricInput(
                            value.statTop1, value.statTop3, value.statReciprocalRank,
                            value.statBrier, value.statLogLoss, value.statTop1Confidence
                        ),
                        tiny = if (value.tinyAvailable) MetricInput(
                            value.tinyTop1, value.tinyTop3, value.tinyReciprocalRank,
                            value.tinyBrier, value.tinyLogLoss, value.tinyTop1Confidence
                        ) else null,
                        effective = MetricInput(
                            value.effectiveTop1, value.effectiveTop3, value.effectiveReciprocalRank,
                            value.effectiveBrier, value.effectiveLogLoss, value.effectiveTop1Confidence
                        ),
                        winner = winner
                    ) else old.promotionHoldout,
                    journeyLengthBuckets = old.journeyLengthBuckets.increment(journeyLengthBucket(value.journeyLength)),
                    stageCounts = old.stageCounts.increment(normalizeStage(value.stage)),
                    statInferenceNanosSum = old.statInferenceNanosSum + value.statInferenceNanos.coerceAtLeast(0),
                    tinyInferenceNanosSum = old.tinyInferenceNanosSum + value.tinyInferenceNanos.coerceAtLeast(0)
                )
            )
        }
    }

    suspend fun contributePreset(value: PresetShadowEvaluationEntity) {
        val state = dao.taskModelState(value.profileKey, TelemetryV3Task.PRESET_RANKING.name)
        mutate(
            profileKey = value.profileKey,
            task = TelemetryV3Task.PRESET_RANKING,
            occurredEpochDay = value.occurredEpochDay,
            naturalHoldout = value.naturalHoldoutEligible && value.evaluationSource == "ORGANIC",
            featureSchemaVersion = value.featureSchemaVersion,
            outputSchemaVersion = state?.outputSchemaVersion ?: 1
        ) { stored ->
            val old = stored.ranking ?: V3RankingAggregate()
            val statLoss = binaryLogLoss(value.statScore, value.label)
            val tinyLoss = binaryLogLoss(value.tinyScore, value.label)
            stored.copy(
                ranking = old.copy(
                    naturalSampleCount = old.naturalSampleCount + if (value.evaluationSource == "ORGANIC") 1 else 0,
                    assistedSampleCount = old.assistedSampleCount + if (value.evaluationSource == "ORGANIC") 0 else 1,
                    statistical = old.statistical.add(value.statScore, value.label),
                    tinyMlp = old.tinyMlp.add(value.tinyScore, value.label),
                    recentBaseline = old.recentBaseline.add(value.recentBaselineScore, value.label),
                    frequencyBaseline = old.frequencyBaseline.add(value.frequencyBaselineScore, value.label),
                    tinyVsStat = old.tinyVsStat.addWinner(
                        when {
                            tinyLoss + WIN_EPSILON < statLoss -> Winner.FIRST
                            statLoss + WIN_EPSILON < tinyLoss -> Winner.SECOND
                            else -> Winner.TIE
                        }
                    ),
                    stageCounts = old.stageCounts.increment(normalizeStage(state?.stage)),
                    healthCounts = old.healthCounts.increment(normalizeHealth(state?.healthState)),
                    lambdaBucketCounts = old.lambdaBucketCounts.increment(lambdaBucket(state?.mixedLambda)),
                    eceSum = old.eceSum + (state?.ece?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0),
                    eceSampleCount = old.eceSampleCount + if (state?.ece?.isFinite() == true) 1 else 0
                )
            )
        }
    }

    suspend fun contributeCandidate(value: CandidateShadowEvaluationEntity) {
        mutate(
            profileKey = value.profileKey,
            task = TelemetryV3Task.CANDIDATE_SHADOW,
            occurredEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY,
            naturalHoldout = true,
            featureSchemaVersion = FeatureExtractor.FEATURE_SCHEMA_VERSION,
            outputSchemaVersion = AppActionCatalog.OUTPUT_SCHEMA_VERSION
        ) { stored ->
            val old = stored.candidateShadow ?: V3CandidateShadowAggregate()
            stored.copy(
                candidateShadow = old.copy(
                    activeTop3Hit = old.activeTop3Hit + value.activeTop3,
                    candidateTop3Hit = old.candidateTop3Hit + value.candidateTop3,
                    activeMrrSum = old.activeMrrSum + value.activeMrr,
                    candidateMrrSum = old.candidateMrrSum + value.candidateMrr,
                    activeBrierSum = old.activeBrierSum + value.activeBrier,
                    candidateBrierSum = old.candidateBrierSum + value.candidateBrier,
                    activeLogLossSum = old.activeLogLossSum + value.activeLogLoss,
                    candidateLogLossSum = old.candidateLogLossSum + value.candidateLogLoss,
                    candidateVsActive = old.candidateVsActive.addWinner(
                        when {
                            value.candidateLogLoss + WIN_EPSILON < value.activeLogLoss -> Winner.FIRST
                            value.activeLogLoss + WIN_EPSILON < value.candidateLogLoss -> Winner.SECOND
                            else -> Winner.TIE
                        }
                    ),
                    activeInferenceNanosSum = old.activeInferenceNanosSum + value.activeInferenceNanos.coerceAtLeast(0),
                    candidateInferenceNanosSum = old.candidateInferenceNanosSum + value.candidateInferenceNanos.coerceAtLeast(0)
                )
            )
        }
    }

    suspend fun recordPresetInteraction(
        profileKey: String,
        event: TelemetryPresetEvent,
        feedbackWeight: Double? = null
    ) {
        val state = dao.taskModelState(profileKey, TelemetryV3Task.PRESET_RANKING.name)
        mutate(
            profileKey = profileKey,
            task = TelemetryV3Task.PRESET_RANKING,
            occurredEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY,
            naturalHoldout = false,
            featureSchemaVersion = state?.featureSchemaVersion ?: 1,
            outputSchemaVersion = state?.outputSchemaVersion ?: 1
        ) { stored ->
            val old = stored.ranking ?: V3RankingAggregate()
            stored.copy(
                ranking = old.copy(
                    exposedCount = old.exposedCount + if (event == TelemetryPresetEvent.EXPOSED) 1 else 0,
                    appliedCount = old.appliedCount + if (event == TelemetryPresetEvent.APPLIED) 1 else 0,
                    queryConfirmedCount = old.queryConfirmedCount + if (event == TelemetryPresetEvent.QUERY_CONFIRMED) 1 else 0,
                    replacedCount = old.replacedCount + if (event == TelemetryPresetEvent.REPLACED) 1 else 0,
                    removedCount = old.removedCount + if (event == TelemetryPresetEvent.REMOVED) 1 else 0,
                    expiredWithoutLabelCount = old.expiredWithoutLabelCount +
                        if (event == TelemetryPresetEvent.EXPIRED_WITHOUT_LABEL) 1 else 0,
                    assistedFeedbackWeightSum = old.assistedFeedbackWeightSum +
                        (feedbackWeight?.coerceIn(0.0, 1.0) ?: 0.0)
                )
            )
        }
    }

    suspend fun recordDelivery(
        profileKey: String,
        lane: SuggestionDeliveryLane,
        event: TelemetryDeliveryEvent,
        blockReason: String? = null,
        assistedRewardWeight: Double? = null,
        latencyMs: Long? = null
    ) {
        mutate(
            profileKey = profileKey,
            task = TelemetryV3Task.DELIVERY,
            occurredEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY,
            naturalHoldout = false,
            featureSchemaVersion = FeatureExtractor.FEATURE_SCHEMA_VERSION,
            outputSchemaVersion = AppActionCatalog.OUTPUT_SCHEMA_VERSION
        ) { stored ->
            val old = stored.delivery ?: V3DeliveryAggregate()
            val lanes = old.lanes.associateBy(V3DeliveryLaneAggregate::lane).toMutableMap()
            val previous = lanes[lane.name] ?: V3DeliveryLaneAggregate(lane.name)
            lanes[lane.name] = previous.copy(
                opportunities = previous.opportunities + if (event == TelemetryDeliveryEvent.OPPORTUNITY) 1 else 0,
                modelGatePassed = previous.modelGatePassed + if (event == TelemetryDeliveryEvent.MODEL_GATE_PASSED) 1 else 0,
                enteredVisibleSurface = previous.enteredVisibleSurface + if (event == TelemetryDeliveryEvent.ENTERED_VISIBLE_SURFACE) 1 else 0,
                clicked = previous.clicked + if (event == TelemetryDeliveryEvent.CLICKED) 1 else 0,
                completed = previous.completed + if (event == TelemetryDeliveryEvent.COMPLETED) 1 else 0,
                dismissed = previous.dismissed + if (event == TelemetryDeliveryEvent.DISMISSED) 1 else 0,
                timedOut = previous.timedOut + if (event == TelemetryDeliveryEvent.TIMED_OUT) 1 else 0,
                blocked = if (event == TelemetryDeliveryEvent.BLOCKED) {
                    previous.blocked.increment(normalizeDeliveryBlockReason(blockReason))
                } else previous.blocked,
                assistedRewardCount = previous.assistedRewardCount + if (event == TelemetryDeliveryEvent.ASSISTED_REWARD) 1 else 0,
                assistedRewardWeightSum = previous.assistedRewardWeightSum +
                    if (event == TelemetryDeliveryEvent.ASSISTED_REWARD) assistedRewardWeight?.coerceIn(0.0, 1.0) ?: 0.0 else 0.0,
                latencyBuckets = latencyMs?.let { previous.latencyBuckets.increment(latencyBucket(it)) }
                    ?: previous.latencyBuckets
            )
            stored.copy(delivery = V3DeliveryAggregate(lanes.values.sortedBy(V3DeliveryLaneAggregate::lane)))
        }
    }

    fun decodeAggregate(json: String): StoredTelemetryV3Aggregate =
        aggregateCodec.decode(json) ?: StoredTelemetryV3Aggregate()

    internal fun decodeAggregate(
        json: String,
        expectedTask: TelemetryV3Task
    ): StoredTelemetryV3Aggregate? = aggregateCodec.decode(json, expectedTask)

    private suspend fun mutate(
        profileKey: String,
        task: TelemetryV3Task,
        occurredEpochDay: Long,
        naturalHoldout: Boolean,
        featureSchemaVersion: Int,
        outputSchemaVersion: Int,
        transform: (StoredTelemetryV3Aggregate) -> StoredTelemetryV3Aggregate
    ) = taskLocks.getOrPut("$profileKey:${task.name}") { Mutex() }.withLock {
        val lifecycle = dao.telemetryState(profileKey)
            ?.takeIf { it.lifecycleState == "ACTIVE" }
            ?: return
        val now = System.currentTimeMillis()
        var open = dao.openTelemetryV3AggregateWindow(profileKey, lifecycle.consentLifecycleId, task.name)
        var decoded = open?.let { aggregateCodec.decode(it.aggregateJson, task) }
        if (open != null && (
                !sameBinding(open, lifecycle, featureSchemaVersion, outputSchemaVersion) || decoded == null
            )
        ) {
            dao.transitionTelemetryV3AggregateWindow(open.windowId, "OPEN", "SUPPRESSED", now)
            open = null
            decoded = null
        }
        val current = open ?: TelemetryV3AggregateWindowEntity(
            windowId = UUID.randomUUID().toString(),
            profileKey = profileKey,
            consentLifecycleId = lifecycle.consentLifecycleId,
            telemetryId = lifecycle.telemetryId,
            modelGenerationId = lifecycle.modelGenerationId,
            task = task.name,
            windowStartEpochDay = occurredEpochDay,
            windowEndEpochDay = occurredEpochDay,
            sampleCount = 0,
            naturalHoldoutSampleCount = 0,
            aggregateJson = aggregateCodec.encode(emptyAggregate(task)),
            appVersionCode = BuildConfig.VERSION_CODE,
            featureSchemaVersion = featureSchemaVersion,
            outputSchemaVersion = outputSchemaVersion,
            metricSchemaVersion = TELEMETRY_V3_METRIC_SCHEMA_VERSION,
            state = "OPEN",
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        ).also { dao.insertTelemetryV3AggregateWindow(it) }
        val count = current.sampleCount + 1
        dao.updateTelemetryV3AggregateWindow(
            current.copy(
                windowEndEpochDay = maxOf(current.windowEndEpochDay, occurredEpochDay),
                sampleCount = count,
                naturalHoldoutSampleCount = current.naturalHoldoutSampleCount + if (naturalHoldout) 1 else 0,
                aggregateJson = aggregateCodec.encode(transform(decoded ?: emptyAggregate(task))),
                state = if (count >= TELEMETRY_V3_MIN_TASK_SAMPLES) "CLOSED" else "OPEN",
                updatedAtEpochMs = now
            )
        )
    }

    private fun sameBinding(
        window: TelemetryV3AggregateWindowEntity,
        lifecycle: TelemetryStateEntity,
        featureSchemaVersion: Int,
        outputSchemaVersion: Int
    ): Boolean = window.telemetryId == lifecycle.telemetryId &&
        window.modelGenerationId == lifecycle.modelGenerationId &&
        window.appVersionCode == BuildConfig.VERSION_CODE &&
        window.featureSchemaVersion == featureSchemaVersion &&
        window.outputSchemaVersion == outputSchemaVersion &&
        window.metricSchemaVersion == TELEMETRY_V3_METRIC_SCHEMA_VERSION

    private fun emptyAggregate(task: TelemetryV3Task) = when (task) {
        TelemetryV3Task.NEXT_ACTION, TelemetryV3Task.JOURNEY_GOAL ->
            StoredTelemetryV3Aggregate(classification = V3ClassificationAggregate())
        TelemetryV3Task.PRESET_RANKING -> StoredTelemetryV3Aggregate(ranking = V3RankingAggregate())
        TelemetryV3Task.CANDIDATE_SHADOW ->
            StoredTelemetryV3Aggregate(candidateShadow = V3CandidateShadowAggregate())
        TelemetryV3Task.DELIVERY -> StoredTelemetryV3Aggregate(delivery = V3DeliveryAggregate())
    }

    private fun V3ModelMetricAggregate.add(
        top1: Int,
        top3: Int,
        reciprocalRank: Double,
        brier: Double,
        logLoss: Double,
        confidence: Double?
    ): V3ModelMetricAggregate = copy(
        sampleCount = sampleCount + 1,
        top1Correct = top1Correct + top1.coerceIn(0, 1),
        top3Hit = top3Hit + top3.coerceIn(0, 1),
        reciprocalRankSum = reciprocalRankSum + reciprocalRank.coerceIn(0.0, 1.0),
        brierSum = brierSum + brier.coerceAtLeast(0.0),
        logLossSum = logLossSum + logLoss.coerceAtLeast(0.0),
        calibration = calibration.addCalibration(confidence ?: 0.0, top1 == 1)
    )

    private fun V3PromotionHoldoutAggregate.add(
        stat: MetricInput,
        tiny: MetricInput?,
        effective: MetricInput,
        winner: Winner
    ): V3PromotionHoldoutAggregate = copy(
        statistical = statistical.add(stat),
        tinyMlp = if (tiny != null) tinyMlp.add(tiny) else tinyMlp,
        effective = this.effective.add(effective),
        tinyVsStat = tinyVsStat.addWinner(winner)
    )

    private fun V3ModelMetricAggregate.add(value: MetricInput): V3ModelMetricAggregate = add(
        value.top1,
        value.top3,
        value.reciprocalRank,
        value.brier,
        value.logLoss,
        value.confidence
    )

    private fun V3BinaryScoreAggregate.add(score: Float, label: Boolean): V3BinaryScoreAggregate {
        val probability = score.toDouble().coerceIn(0.0, 1.0)
        val expected = if (label) 1.0 else 0.0
        return copy(
            sampleCount = sampleCount + 1,
            positiveCount = positiveCount + if (label) 1 else 0,
            scoreSum = scoreSum + probability,
            brierSum = brierSum + (probability - expected) * (probability - expected),
            logLossSum = logLossSum + binaryLogLoss(probability.toFloat(), label),
            calibration = calibration.addCalibration(probability, (probability >= 0.5) == label)
        )
    }

    private fun List<V3CalibrationBin>.addCalibration(confidence: Double, correct: Boolean): List<V3CalibrationBin> {
        val source = takeIf { size == CALIBRATION_BIN_COUNT } ?: emptyCalibrationBins()
        val index = (confidence.coerceIn(0.0, 0.999999) * CALIBRATION_BIN_COUNT).toInt()
        return source.mapIndexed { current, bin ->
            if (current == index) bin.copy(
                sampleCount = bin.sampleCount + 1,
                correctCount = bin.correctCount + if (correct) 1 else 0
            ) else bin
        }
    }

    private fun List<V3NamedCount>.increment(name: String): List<V3NamedCount> {
        val values = associateBy(V3NamedCount::name).toMutableMap()
        val old = values[name]
        values[name] = V3NamedCount(name, (old?.count ?: 0) + 1)
        return values.values.sortedBy(V3NamedCount::name)
    }

    private fun V3PairwiseAggregate.addWinner(winner: Winner): V3PairwiseAggregate = when (winner) {
        Winner.FIRST -> copy(firstWins = firstWins + 1)
        Winner.SECOND -> copy(secondWins = secondWins + 1)
        Winner.TIE -> copy(ties = ties + 1)
        Winner.NONE -> this
    }

    private fun binaryLogLoss(score: Float, label: Boolean): Double {
        val probability = score.toDouble().coerceIn(1e-7, 1.0 - 1e-7)
        return if (label) -ln(probability) else -ln(1.0 - probability)
    }

    private fun normalizeStage(value: String?): String = value?.takeIf(STAGES::contains) ?: "UNKNOWN"
    private fun normalizeTier(value: String?): String = value?.takeIf(TIERS::contains) ?: "UNKNOWN"
    private fun normalizeHealth(value: String?): String = value?.takeIf(HEALTH_STATES::contains) ?: "UNKNOWN"

    private fun lambdaBucket(value: Float?): String = when (value) {
        0f -> "0"
        0.25f -> "25"
        0.5f -> "50"
        0.75f -> "75"
        1f -> "100"
        else -> "OTHER"
    }

    private fun journeyLengthBucket(length: Int): String = when (length) {
        1 -> "1"
        2 -> "2"
        3 -> "3"
        4 -> "4"
        else -> "5_PLUS"
    }

    private fun normalizeDeliveryBlockReason(reason: String?): String = when (reason) {
        "HOLDOUT" -> "HOLDOUT"
        "BELOW_CONFIDENCE_THRESHOLD" -> "MODEL_CONFIDENCE"
        "INSUFFICIENT_PROBABILITY_MARGIN" -> "MODEL_MARGIN"
        "NON_SUGGESTIBLE_OUTPUT_DOMINATES" -> "NON_SUGGESTIBLE_DOMINATES"
        "INTERVAL" -> "INTERVAL"
        "SAFETY_GATE" -> "SAFETY"
        "ENTRY_UNAVAILABLE", "TARGETED_ACTION_UNAVAILABLE_OR_UNSAFE" -> "ENTRY_UNAVAILABLE"
        "OCCUPIED", "SURFACE_TEMPORARILY_OCCUPIED" -> "OCCUPIED"
        "EXPIRED" -> "EXPIRED"
        "STALE_GENERATION" -> "STALE"
        "SURFACE_OR_SAFETY_GATE_REJECTED" -> "SAFETY"
        else -> "OTHER"
    }

    private fun latencyBucket(value: Long): String = when {
        value < 1_000L -> "LT_1S"
        value < 5_000L -> "1_TO_5S"
        value < 15_000L -> "5_TO_15S"
        value <= 60_000L -> "15_TO_60S"
        else -> "GT_60S"
    }

    private enum class Winner { FIRST, SECOND, TIE, NONE }

    private data class MetricInput(
        val top1: Int,
        val top3: Int,
        val reciprocalRank: Double,
        val brier: Double,
        val logLoss: Double,
        val confidence: Double
    )

    private companion object {
        const val WIN_EPSILON = 1e-6
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
