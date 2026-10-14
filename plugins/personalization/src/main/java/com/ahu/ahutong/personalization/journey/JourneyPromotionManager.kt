package com.ahu.ahutong.personalization.journey

import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.model.ModelTask
import com.ahu.ahutong.personalization.promotion.PromotionStage
import com.ahu.ahutong.personalization.storage.BehaviorDao
import com.ahu.ahutong.personalization.storage.JourneyShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.TaskModelStateEntity
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JourneyPromotionSnapshot(
    val stage: PromotionStage,
    val lambda: Float,
    val activeCheckpointId: String?,
    val candidateCheckpointId: String?,
    val healthState: String
)

class JourneyPromotionManager constructor(
    private val dao: BehaviorDao,
    private val store: JourneyModelStateStore
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun snapshot(profileKey: String): JourneyPromotionSnapshot {
        val state = ensureState(profileKey)
        return JourneyPromotionSnapshot(
            PromotionStage.valueOf(state.stage),
            if (state.healthState == "HEALTHY") state.mixedLambda else 0f,
            state.activeCheckpointId,
            state.candidateCheckpointId,
            state.healthState
        )
    }

    suspend fun evaluate(profileKey: String) = locks.getOrPut(profileKey) { Mutex() }.withLock {
        val model = store.state(profileKey)
        var state = ensureState(profileKey)
        val total = dao.journeyTrainingSampleCount(profileKey)
        val nonNone = dao.journeyNonNoneSampleCount(profileKey)
        val families = dao.journeyTargetFamilyCount(profileKey)
        val evaluations = model.candidate?.let { candidate ->
            dao.journeyEvaluationsAfter(
                profileKey,
                candidate.checkpointId,
                state.lastEvaluationSeq,
                WINDOW_SIZE * REQUIRED_WINDOWS
            )
        }.orEmpty()
        val candidateReady = model.candidate != null && evaluations.size == WINDOW_SIZE * REQUIRED_WINDOWS
        val windows = if (candidateReady) evaluations.chunked(WINDOW_SIZE) else emptyList()
        val allPassing = windows.size == REQUIRED_WINDOWS && windows.all(::windowPasses)
        val ece = if (windows.isEmpty()) 1.0 else expectedCalibrationError(windows.flatten())
        val healthy = total >= MIN_SAMPLES && nonNone >= MIN_NON_NONE && families >= MIN_FAMILIES &&
            allPassing && ece <= ECE_LIMIT

        state = state.copy(
            activeCheckpointId = model.active.checkpointId,
            candidateCheckpointId = model.candidate?.checkpointId,
            trainingRevision = model.training.trainingRevision,
            validSampleCount = total,
            nonNoneSampleCount = nonNone,
            targetFamilyCount = families,
            ece = ece,
            consecutivePassingWindows = if (!candidateReady) state.consecutivePassingWindows else if (healthy) state.consecutivePassingWindows + 1 else 0,
            consecutiveFailingWindows = if (!candidateReady) state.consecutiveFailingWindows else if (healthy) 0 else state.consecutiveFailingWindows + 1,
            lastEvaluationSeq = if (candidateReady) evaluations.last().evaluationSeq else state.lastEvaluationSeq,
            updatedAtEpochMs = System.currentTimeMillis()
        )

        if (healthy && model.candidate != null) {
            val activated = store.activateCandidate(profileKey, model.candidate.checkpointId)
            state = when (PromotionStage.valueOf(state.stage)) {
                PromotionStage.SHADOW -> state.copy(stage = PromotionStage.ELIGIBLE.name, mixedLambda = 0f)
                PromotionStage.ELIGIBLE -> state.copy(stage = PromotionStage.MIXED.name, mixedLambda = 0.25f)
                PromotionStage.MIXED -> if (state.consecutivePassingWindows >= 3) {
                    state.copy(stage = PromotionStage.PRIMARY.name, mixedLambda = 1f)
                } else state.copy(mixedLambda = 0.5f)
                PromotionStage.PRIMARY -> state.copy(mixedLambda = 1f)
            }.copy(
                activeCheckpointId = activated.active.checkpointId,
                candidateCheckpointId = null,
                modelGeneration = state.modelGeneration + 1,
                lastTransitionReason = "LOCAL_QUALITY_GATE_PASSED"
            )
        } else if (candidateReady && !healthy) {
            store.discardCandidate(profileKey)
            state = if (state.consecutiveFailingWindows >= 2 && state.stage != PromotionStage.SHADOW.name) {
                state.copy(
                    stage = PromotionStage.SHADOW.name,
                    mixedLambda = 0f,
                    candidateCheckpointId = null,
                    lastTransitionReason = "QUALITY_OR_CALIBRATION_REGRESSION"
                )
            } else {
                state.copy(
                    candidateCheckpointId = null,
                    lastTransitionReason = "CANDIDATE_QUALITY_GATE_FAILED"
                )
            }
        }
        dao.upsertTaskModelState(state)
    }

    suspend fun markUnhealthy(profileKey: String, reason: String) {
        val state = ensureState(profileKey)
        dao.upsertTaskModelState(
            state.copy(
                stage = PromotionStage.SHADOW.name,
                mixedLambda = 0f,
                healthState = "LATCHED_STAT_ONLY",
                lastTransitionReason = reason,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private suspend fun ensureState(profileKey: String): TaskModelStateEntity {
        val model = store.loadOrCreate(profileKey)
        val existing = dao.taskModelState(profileKey, ModelTask.JOURNEY_GOAL.name)
        if (existing != null &&
            existing.featureSchemaVersion == FeatureExtractor.FEATURE_SCHEMA_VERSION &&
            existing.outputSchemaVersion == JourneyGoalCatalog.OUTPUT_SCHEMA_VERSION &&
            existing.activeCheckpointId == model.active.checkpointId
        ) return existing
        val checkpointRecovered = existing != null &&
            existing.featureSchemaVersion == FeatureExtractor.FEATURE_SCHEMA_VERSION &&
            existing.outputSchemaVersion == JourneyGoalCatalog.OUTPUT_SCHEMA_VERSION
        return TaskModelStateEntity(
            profileKey,
            ModelTask.JOURNEY_GOAL.name,
            (existing?.modelGeneration ?: 0) + 1,
            FeatureExtractor.FEATURE_SCHEMA_VERSION,
            JourneyGoalCatalog.OUTPUT_SCHEMA_VERSION,
            model.active.checkpointId,
            model.candidate?.checkpointId,
            model.training.trainingRevision,
            PromotionStage.SHADOW.name,
            0f,
            0,
            0,
            dao.journeyTrainingSampleCount(profileKey),
            dao.journeyNonNoneSampleCount(profileKey),
            dao.journeyTargetFamilyCount(profileKey),
            1.0,
            if (checkpointRecovered) "LATCHED_STAT_ONLY" else "HEALTHY",
            when {
                existing == null -> "INITIALIZED_SHADOW"
                checkpointRecovered -> "CHECKPOINT_RECOVERED_STAT_ONLY"
                else -> "SCHEMA_INCOMPATIBLE_ISOLATED"
            },
            0,
            System.currentTimeMillis()
        ).also { dao.upsertTaskModelState(it) }
    }

    private fun windowPasses(values: List<JourneyShadowEvaluationEntity>): Boolean {
        if (values.size < WINDOW_SIZE) return false
        val paired = values.filter { it.tinyInferenceNanos > 0 }
        if (paired.size < WINDOW_SIZE) return false
        val tinyMrr = paired.map(JourneyShadowEvaluationEntity::tinyReciprocalRank).average()
        val statMrr = paired.map(JourneyShadowEvaluationEntity::statReciprocalRank).average()
        val inferenceP95 = paired.map(JourneyShadowEvaluationEntity::tinyInferenceNanos).sorted()
            .let { it[((it.size - 1) * 0.95).toInt()] }
        val perTargetHealthy = paired.groupBy(JourneyShadowEvaluationEntity::trueLabel).values.all { target ->
            if (target.size < 5) true else {
                val tiny = target.count { it.tinyTop1 == 1 } / target.size.toDouble()
                val stat = target.count { it.statTop1 == 1 } / target.size.toDouble()
                tiny + PER_TARGET_REGRESSION_LIMIT >= stat
            }
        }
        return tinyMrr > statMrr && inferenceP95 <= INFERENCE_P95_LIMIT_NANOS && perTargetHealthy
    }

    private fun expectedCalibrationError(values: List<JourneyShadowEvaluationEntity>): Double {
        if (values.isEmpty()) return 1.0
        return (0 until 10).sumOf { bucket ->
            val lower = bucket / 10.0
            val upper = (bucket + 1) / 10.0
            val selected = values.filter { value ->
                val confidence = value.tinyTop1Confidence.coerceIn(0.0, 1.0)
                confidence >= lower && (confidence < upper || bucket == 9)
            }
            if (selected.isEmpty()) 0.0 else {
                val accuracy = selected.count { it.tinyTop1 == 1 } / selected.size.toDouble()
                val confidence = selected.map { it.tinyTop1Confidence.coerceIn(0.0, 1.0) }.average()
                selected.size / values.size.toDouble() * abs(accuracy - confidence)
            }
        }
    }

    private companion object {
        const val MIN_SAMPLES = 128
        const val MIN_NON_NONE = 64
        const val MIN_FAMILIES = 3
        const val WINDOW_SIZE = 64
        const val REQUIRED_WINDOWS = 3
        const val ECE_LIMIT = 0.08
        const val PER_TARGET_REGRESSION_LIMIT = 0.10
        const val INFERENCE_P95_LIMIT_NANOS = 8_000_000L
    }
}
