package com.ahu.ahutong.personalization.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "behavior_event",
    indices = [
        Index(value = ["profileKey", "sequenceNo"]),
        Index(value = ["profileKey", "occurredAtEpochMs"]),
        Index(value = ["resolvedDecisionId"], unique = true),
        Index(value = ["profileKey", "actionInstanceId", "eventType"], unique = true)
    ]
)
data class BehaviorEventEntity(
    @PrimaryKey val eventId: String,
    val actionInstanceId: String,
    val profileKey: String,
    val sessionId: String,
    val processInstanceId: String,
    val sequenceNo: Long,
    val eventType: String,
    val actionId: String?,
    val source: String,
    val occurredAtEpochMs: Long,
    val occurredAtElapsedMs: Long,
    val sessionElapsedMs: Long,
    val triggerDecisionId: String?,
    val resolvedDecisionId: String?,
    val timeBucket: Int,
    val dayType: String,
    val balanceBucket: String,
    val daysToExamBucket: String,
    val contextSchemaVersion: Int
)

@Entity(
    tableName = "pending_prediction",
    indices = [
        Index(value = ["profileKey", "resolutionStatus", "labelDeadlineElapsedMs"]),
        Index(value = ["profileKey", "sequenceNo"]),
        Index(value = ["profileKey", "sessionId", "triggerEventId"], unique = true)
    ]
)
data class PendingPredictionEntity(
    @PrimaryKey val decisionId: String,
    val profileKey: String,
    val sessionId: String,
    val processInstanceId: String,
    val sequenceNo: Long,
    val triggerEventId: String,
    val previousAction: String?,
    val createdAtEpochMs: Long,
    val createdAtElapsedMs: Long,
    val labelDeadlineElapsedMs: Long,
    val labelWindowPolicyVersion: Int,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val features: ByteArray,
    val availabilityMask: ByteArray,
    val inputDigest: String,
    val contextSnapshotJson: String,
    val preparationState: String,
    val preparationFailure: String?,
    val statProbabilities: ByteArray?,
    val tinyProbabilities: ByteArray?,
    val recentBaselineProbabilities: ByteArray?,
    val timeBaselineProbabilities: ByteArray?,
    val statModelVersion: Int?,
    val tinyModelVersion: Int?,
    val activeCheckpointId: String?,
    val activeCheckpointChecksum: String?,
    val candidateCheckpointId: String?,
    val candidateCheckpointChecksum: String?,
    val candidateProbabilities: ByteArray?,
    val candidateInferenceNanos: Long?,
    val statInferenceNanos: Long?,
    val tinyInferenceNanos: Long?,
    val promotionStageAtDecision: String,
    val effectiveDecisionTierAtDecision: String,
    val mixedLambda: Float,
    val isPromotionHoldout: Boolean,
    val interventionState: String,
    val resolutionStatus: String,
    val finalOrganicTarget: String?,
    val resolvedByEventId: String?,
    val effectiveProbabilities: ByteArray? = null
)

@Entity(
    tableName = "product_execution_lease",
    indices = [
        Index(value = ["profileKey", "decisionId"], unique = true),
        Index(value = ["profileKey", "executionId"], unique = true)
    ]
)
data class ProductExecutionLeaseEntity(
    @PrimaryKey val executionId: String,
    val decisionId: String,
    val profileKey: String,
    val sessionId: String,
    val processInstanceId: String,
    val actionId: String,
    val interventionType: String,
    val source: String,
    val route: String?,
    val profileGeneration: Long,
    val loginGeneration: Long,
    val preparedAtSequenceNo: Long,
    val executionEpoch: Long,
    val createdAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
    val state: String
)

@Entity(
    tableName = "action_stat",
    primaryKeys = ["profileKey", "contextKey", "actionId"],
    indices = [Index(value = ["profileKey", "actionId"])]
)
data class ActionStatEntity(
    val profileKey: String,
    val contextKey: String,
    val actionId: String,
    val positiveMass: Double,
    val exposureMass: Double,
    val updatedAtEpochDay: Long
)

@Entity(
    tableName = "training_sample",
    indices = [
        Index(value = ["profileKey", "sampleId"], unique = true),
        Index(value = ["profileKey", "decisionId"], unique = true),
        Index(value = ["profileKey", "targetIndex"]),
        Index(value = ["profileKey", "occurredEpochDay"])
    ]
)
data class TrainingSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sampleId: String,
    val profileKey: String,
    val decisionId: String,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val features: ByteArray,
    val targetIndex: Int,
    val targetActionId: String,
    val actionFamily: String,
    val occurredEpochDay: Long,
    val replayPriority: Float,
    val trainingCount: Int,
    val labelSource: String,
    val deliveryLane: String = "UNKNOWN",
    val naturalHoldoutEligible: Boolean = false
)

@Entity(
    tableName = "training_batch_journal",
    indices = [Index(value = ["profileKey", "state", "createdAtEpochMs"])]
)
data class TrainingBatchJournalEntity(
    @PrimaryKey val batchId: String,
    val profileKey: String,
    val expectedTrainingRevision: Long,
    val selectedRowIds: String,
    val state: String,
    val createdAtEpochMs: Long,
    val committedAtEpochMs: Long?
)

@Entity(
    tableName = "shadow_evaluation",
    indices = [
        Index(value = ["profileKey", "evaluationSeq"], unique = true),
        Index(value = ["profileKey", "decisionId"], unique = true),
        Index(value = ["profileKey", "occurredEpochDay"])
    ]
)
data class ShadowEvaluationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileKey: String,
    val evaluationSeq: Long,
    val decisionId: String,
    val occurredEpochDay: Long,
    val trueLabel: String,
    val isOrganicNonNone: Boolean,
    val statTop1: Int,
    val statTop3: Int,
    val statReciprocalRank: Double,
    val statBrier: Double,
    val statLogLoss: Double,
    val statTop1Confidence: Double,
    val tinyTop1: Int,
    val tinyTop3: Int,
    val tinyReciprocalRank: Double,
    val tinyBrier: Double,
    val tinyLogLoss: Double,
    val tinyTop1Confidence: Double,
    val effectiveTop1: Int,
    val effectiveTop3: Int,
    val effectiveReciprocalRank: Double,
    val effectiveBrier: Double,
    val effectiveLogLoss: Double,
    val effectiveTop1Confidence: Double,
    val recentTop1: Int,
    val recentReciprocalRank: Double,
    val recentTop3: Int,
    val recentBrier: Double,
    val recentLogLoss: Double,
    val recentTop1Confidence: Double,
    val timeTop1: Int,
    val timeReciprocalRank: Double,
    val timeTop3: Int,
    val timeBrier: Double,
    val timeLogLoss: Double,
    val timeTop1Confidence: Double,
    val winner: String,
    val paired: Boolean,
    val tinyPredictionStatus: String,
    val statInferenceNanos: Long,
    val tinyInferenceNanos: Long,
    val trainingNanos: Long,
    val modelSizeBytes: Long,
    val promotionEligible: Boolean,
    val telemetryEligible: Boolean,
    val rejectionReason: String?,
    val stage: String,
    val tier: String,
    val activeCheckpointId: String?,
    val isHoldout: Boolean,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val appVersionCode: Int,
    val statModelVersion: Int,
    val tinyModelVersion: Int,
    val trainingConfigVersion: Int,
    val metricSchemaVersion: Int
)

@Entity(
    tableName = "candidate_shadow_evaluation",
    indices = [Index(value = ["profileKey", "evaluationSeq"], unique = true)]
)
data class CandidateShadowEvaluationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileKey: String,
    val evaluationSeq: Long,
    val decisionId: String,
    val activeCheckpointId: String,
    val candidateCheckpointId: String,
    val activeChecksum: String,
    val candidateChecksum: String,
    val trueLabel: String,
    val activeTop3: Int,
    val candidateTop3: Int,
    val activeMrr: Double,
    val candidateMrr: Double,
    val activeBrier: Double,
    val candidateBrier: Double,
    val activeLogLoss: Double,
    val candidateLogLoss: Double,
    val activeConfidence: Double,
    val candidateConfidence: Double,
    val activeInferenceNanos: Long,
    val candidateInferenceNanos: Long,
    val candidateStatus: String,
    val consumed: Boolean
)

@Entity(tableName = "tiny_promotion_state")
data class TinyPromotionStateEntity(
    @PrimaryKey val profileKey: String,
    val holdoutSeed: String,
    val stage: String,
    val mixedLambda: Float,
    val modelGenerationVersion: Long,
    val stageGeneration: Long,
    val transitionSequence: Long,
    val enteredEpochDay: Long,
    val evidenceHighWatermark: Long,
    val activeCheckpointId: String?,
    val activeChecksum: String?,
    val candidateCheckpointId: String?,
    val trainingRevision: Long,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val promotionConfigVersion: Int,
    val consecutivePassingWindows: Int,
    val consecutiveFailingWindows: Int,
    val cooldownUntilEpochDay: Long,
    val minimumNewEvidenceSeq: Long,
    val healthState: String,
    val lastTransitionReason: String,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "tiny_runtime_health_state")
data class TinyRuntimeHealthStateEntity(
    @PrimaryKey val profileKey: String,
    val recentAttemptBits: Long,
    val recentAttemptCount: Int,
    val failureEpochDay: Long,
    val failuresToday: Int,
    val totalFailures: Long,
    val lastCheckpointId: String?,
    val lastFailureCode: String?,
    val lastFailureEpochMs: Long?,
    val lastSuccessEpochMs: Long?
)

@Entity(
    tableName = "promotion_evaluation_window",
    indices = [Index(value = ["profileKey", "windowId"], unique = true)]
)
data class PromotionEvaluationWindowEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileKey: String,
    val windowId: String,
    val purpose: String,
    val stage: String,
    val checkpointId: String?,
    val startEvaluationSeq: Long,
    val endEvaluationSeq: Long,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val eligibleSampleCount: Int,
    val organicNonNoneSampleCount: Int,
    val pairedSampleCount: Int,
    val metricsJson: String,
    val ece: Double,
    val perActionDigest: String,
    val inferenceP95Nanos: Long,
    val trainingP95Nanos: Long,
    val status: String,
    val qualified: Boolean,
    val consumedTransitionSequence: Long?
)

@Entity(tableName = "promotion_action_qualification", primaryKeys = ["profileKey", "actionId"])
data class PromotionActionQualificationEntity(
    val profileKey: String,
    val actionId: String,
    val highestQualifiedTier: String,
    val checkpointId: String?,
    val sampleCount: Int,
    val evidenceWindowId: String?,
    val lastRegressionReason: String?
)

@Entity(tableName = "promotion_transition_journal")
data class PromotionTransitionJournalEntity(
    @PrimaryKey val journalId: String,
    val profileKey: String,
    val expectedGeneration: Long,
    val fromStage: String,
    val toStage: String,
    val checkpointId: String?,
    val checksum: String?,
    val state: String,
    val preparedAtEpochMs: Long,
    val committedAtEpochMs: Long?
)

@Entity(tableName = "learning_state")
data class LearningStateEntity(
    @PrimaryKey val profileKey: String,
    val statLearningStartedEpochDay: Long?,
    val tinyTrainingStartedEpochDay: Long?,
    val lastCommittedBatchId: String?,
    val lastTrainingNanos: Long,
    val lastTrainingLoss: Float?,
    val lastGradientNorm: Float?
)

@Entity(
    tableName = "telemetry_report",
    indices = [
        Index(value = ["reportId"], unique = true),
        Index(value = ["batchId"], unique = true),
        Index(value = ["profileKey", "windowId"], unique = true),
        Index(value = ["consentLifecycleId", "state"])
    ]
)
data class TelemetryReportEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val reportId: String,
    val batchId: String,
    val profileKey: String,
    val consentLifecycleId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val windowId: String,
    val payloadJson: String,
    val payloadSha256Hex: String,
    val exactRequestBodyJson: String,
    val bodySha256Hex: String,
    val state: String,
    val attemptCount: Int,
    val nextAttemptEpochMs: Long,
    val lastAttemptEpochDay: Long?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val schemaVersion: Int = 2
)

@Entity(
    tableName = "telemetry_state",
    indices = [Index(value = ["consentLifecycleId"], unique = true)]
)
data class TelemetryStateEntity(
    @PrimaryKey val profileKey: String,
    val consentLifecycleId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val localModelGenerationVersion: Long,
    val encryptedRevocationCapability: String,
    val revocationKeyAlias: String,
    val consentGeneration: Long,
    val aggregationStartEvaluationSeq: Long,
    val lastReportedEvaluationSeq: Long,
    val lastReportCreatedEpochDay: Long?,
    val lifecycleState: String,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "telemetry_aggregate_window",
    indices = [
        Index(value = ["profileKey", "consentLifecycleId", "state", "startEvaluationSeq"]),
        Index(value = ["profileKey", "consentLifecycleId", "startEvaluationSeq"], unique = true)
    ]
)
data class TelemetryAggregateWindowEntity(
    @PrimaryKey val windowId: String,
    val profileKey: String,
    val consentLifecycleId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val activeCheckpointId: String?,
    val startEvaluationSeq: Long,
    val endEvaluationSeq: Long,
    val windowStartEpochDay: Long,
    val windowEndEpochDay: Long,
    val eligibleSampleCount: Int,
    val organicNonNoneSampleCount: Int,
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
    val ties: Int,
    val statInferenceNanosSum: Long,
    val tinyInferenceNanosSum: Long,
    val trainingNanosSum: Long,
    val modelSizeBytesMax: Long,
    val perActionJson: String,
    val appVersionCode: Int,
    val statModelVersion: Int,
    val tinyModelVersion: Int,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val trainingConfigVersion: Int,
    val metricSchemaVersion: Int,
    val state: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "telemetry_v3_aggregate_window",
    indices = [
        Index(value = ["profileKey", "consentLifecycleId", "task", "state", "createdAtEpochMs"]),
        Index(value = ["windowId"], unique = true)
    ]
)
data class TelemetryV3AggregateWindowEntity(
    @PrimaryKey val windowId: String,
    val profileKey: String,
    val consentLifecycleId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val task: String,
    val windowStartEpochDay: Long,
    val windowEndEpochDay: Long,
    val sampleCount: Int,
    val naturalHoldoutSampleCount: Int,
    val aggregateJson: String,
    val appVersionCode: Int,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val metricSchemaVersion: Int,
    val state: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "telemetry_deletion_tombstone")
data class TelemetryDeletionTombstoneEntity(
    @PrimaryKey val deletionId: String,
    val consentLifecycleId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val encryptedRevocationCapability: String,
    val revocationKeyAlias: String,
    val attemptCount: Int,
    val nextAttemptEpochMs: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val state: String
)

@Entity(
    tableName = "semantic_event",
    indices = [
        Index(value = ["profileKey", "sequenceNo"]),
        Index(value = ["profileKey", "semanticId", "committedAtEpochDay"]),
        Index(value = ["profileKey", "mutationBatchId"])
    ]
)
data class SemanticEventEntity(
    @PrimaryKey val eventId: String,
    val profileKey: String,
    val sessionId: String,
    val sequenceNo: Long,
    val eventFamily: String,
    val domainId: String,
    val semanticId: String,
    val changeKind: String,
    val coarseValueBucket: String,
    val route: String?,
    val affectedCandidateSetVersion: Int,
    val source: String,
    val committedAtEpochDay: Long,
    val occurredAtElapsedMs: Long,
    val semanticSchemaVersion: Int,
    val tainted: Boolean,
    val mutationBatchId: String,
    val changeSetId: String
)

@Entity(
    tableName = "semantic_change_set",
    indices = [Index(value = ["profileKey", "sessionId", "state", "lastOccurredAtElapsedMs"])]
)
data class SemanticChangeSetEntity(
    @PrimaryKey val changeSetId: String,
    val profileKey: String,
    val sessionId: String,
    val route: String?,
    val mutationBatchId: String,
    val firstOccurredAtElapsedMs: Long,
    val lastOccurredAtElapsedMs: Long,
    val mutationCount: Int,
    val semanticIdsCsv: String,
    val affectedActionIdsCsv: String,
    val affectedCandidateSetVersion: Int,
    val state: String
)

@Entity(
    tableName = "pending_journey",
    indices = [
        Index(value = ["profileKey", "resolutionStatus", "deadlineElapsedMs"]),
        Index(value = ["profileKey", "sessionId", "triggerEventId"], unique = true),
        Index(value = ["profileKey", "consumedTerminalEventId"], unique = true)
    ]
)
data class PendingJourneyEntity(
    @PrimaryKey val journeyId: String,
    val profileKey: String,
    val sessionId: String,
    val processInstanceId: String,
    val triggerEventId: String,
    val sequenceNo: Long,
    val createdAtEpochMs: Long,
    val createdAtElapsedMs: Long,
    val deadlineElapsedMs: Long,
    val maximumActions: Int,
    val observedActionCount: Int,
    val observedActionIdsCsv: String,
    val lastLeafEnteredAtElapsedMs: Long?,
    val lastLeafActionId: String?,
    val lastLeafEventId: String?,
    val featureSchemaVersion: Int,
    val journeyOutputSchemaVersion: Int,
    val features: ByteArray,
    val inputDigest: String,
    val contextSnapshotJson: String,
    val statProbabilities: ByteArray,
    val tinyProbabilities: ByteArray?,
    val statModelVersion: Int,
    val tinyModelVersion: Int?,
    val statInferenceNanos: Long,
    val tinyInferenceNanos: Long?,
    val activeCheckpointId: String?,
    val candidateCheckpointId: String?,
    val stageAtDecision: String,
    val mixedLambda: Float,
    val isPromotionHoldout: Boolean,
    val interventionState: String,
    val resolutionStatus: String,
    val censorReason: String?,
    val finalTargetActionId: String?,
    val consumedTerminalEventId: String?
)

@Entity(
    tableName = "journey_action_stat",
    primaryKeys = ["profileKey", "contextKey", "targetActionId"],
    indices = [Index(value = ["profileKey", "targetActionId"])]
)
data class JourneyActionStatEntity(
    val profileKey: String,
    val contextKey: String,
    val targetActionId: String,
    val positiveMass: Double,
    val exposureMass: Double,
    val updatedAtEpochDay: Long
)

@Entity(
    tableName = "journey_training_sample",
    indices = [
        Index(value = ["profileKey", "journeyId"], unique = true),
        Index(value = ["profileKey", "targetIndex"]),
        Index(value = ["profileKey", "occurredEpochDay"])
    ]
)
data class JourneyTrainingSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sampleId: String,
    val profileKey: String,
    val journeyId: String,
    val featureSchemaVersion: Int,
    val journeyOutputSchemaVersion: Int,
    val features: ByteArray,
    val targetIndex: Int,
    val targetActionId: String,
    val targetFamily: String,
    val journeyLength: Int,
    val occurredEpochDay: Long,
    val replayPriority: Float,
    val trainingCount: Int,
    val labelSource: String
)

@Entity(
    tableName = "journey_shadow_evaluation",
    indices = [
        Index(value = ["profileKey", "evaluationSeq"], unique = true),
        Index(value = ["profileKey", "journeyId"], unique = true)
    ]
)
data class JourneyShadowEvaluationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileKey: String,
    val evaluationSeq: Long,
    val journeyId: String,
    val occurredEpochDay: Long,
    val trueLabel: String,
    val journeyLength: Int,
    val statTop1: Int,
    val statTop3: Int,
    val statReciprocalRank: Double,
    val statBrier: Double,
    val statLogLoss: Double,
    val statTop1Confidence: Double,
    val tinyTop1: Int,
    val tinyTop3: Int,
    val tinyReciprocalRank: Double,
    val tinyBrier: Double,
    val tinyLogLoss: Double,
    val tinyTop1Confidence: Double,
    val effectiveTop1: Int,
    val effectiveTop3: Int,
    val effectiveReciprocalRank: Double,
    val effectiveBrier: Double,
    val effectiveLogLoss: Double,
    val effectiveTop1Confidence: Double,
    val tinyAvailable: Boolean,
    val promotionEligible: Boolean,
    val tinyCheckpointId: String?,
    val statInferenceNanos: Long,
    val tinyInferenceNanos: Long,
    val stage: String,
    val featureSchemaVersion: Int,
    val journeyOutputSchemaVersion: Int
)

@Entity(
    tableName = "local_parameter_preset",
    indices = [
        Index(value = ["profileKey", "domainId", "fingerprint"], unique = true),
        Index(value = ["profileKey", "domainId", "lastOrganicUsedAtEpochMs"])
    ]
)
data class LocalParameterPresetEntity(
    @PrimaryKey val presetId: String,
    val profileKey: String,
    val domainId: String,
    val fingerprint: String,
    val localPayloadJson: String,
    val coarseFeaturesJson: String,
    val createdAtEpochMs: Long,
    /** Natural commits only. Assisted interactions must not alter recency baselines. */
    val lastOrganicUsedAtEpochMs: Long?,
    val organicUseCount: Int,
    val source: String,
    val schemaVersion: Int
)

@Entity(
    tableName = "preset_usage_stat",
    primaryKeys = ["profileKey", "domainId", "contextKey", "presetId"],
    indices = [Index(value = ["profileKey", "domainId", "presetId"])]
)
data class PresetUsageStatEntity(
    val profileKey: String,
    val domainId: String,
    val contextKey: String,
    val presetId: String,
    val positiveMass: Double,
    val exposureMass: Double,
    val updatedAtEpochDay: Long
)

@Entity(
    tableName = "targeted_prediction_feedback",
    indices = [Index(value = ["profileKey", "predictionTask", "createdAtEpochMs"])]
)
data class TargetedPredictionFeedbackEntity(
    @PrimaryKey val feedbackId: String,
    val profileKey: String,
    val predictionTask: String,
    val decisionId: String,
    val candidateId: String?,
    val feedbackType: String,
    val isolatedFromTraining: Boolean,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "preset_recommendation_interaction",
    indices = [
        Index(value = ["profileKey", "domainId", "state", "shownAtEpochMs"]),
        Index(value = ["profileKey", "opportunityId", "candidateId"], unique = true)
    ]
)
data class PresetRecommendationInteractionEntity(
    @PrimaryKey val interactionId: String,
    val profileKey: String,
    val domainId: String,
    val opportunityId: String,
    val candidateId: String,
    val candidateFingerprint: String,
    val state: String,
    val shownAtEpochMs: Long,
    val appliedAtEpochMs: Long?,
    val resolvedAtEpochMs: Long?,
    val resolutionFingerprint: String?,
    val feedbackWeight: Float?,
    val checkpointId: String?,
    val exposureFeatures: ByteArray? = null,
    val candidateOrdinal: Int? = null,
    val occurredEpochDay: Long? = null
)

@Entity(
    tableName = "task_model_state",
    primaryKeys = ["profileKey", "modelTask"],
    indices = [Index(value = ["profileKey", "stage"])]
)
data class TaskModelStateEntity(
    val profileKey: String,
    val modelTask: String,
    val modelGeneration: Long,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val activeCheckpointId: String?,
    val candidateCheckpointId: String?,
    val trainingRevision: Long,
    val stage: String,
    val mixedLambda: Float,
    val consecutivePassingWindows: Int,
    val consecutiveFailingWindows: Int,
    val validSampleCount: Int,
    val nonNoneSampleCount: Int,
    val targetFamilyCount: Int,
    val ece: Double,
    val healthState: String,
    val lastTransitionReason: String,
    val lastEvaluationSeq: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "task_training_batch_journal",
    indices = [Index(value = ["profileKey", "modelTask", "state", "createdAtEpochMs"])]
)
data class TaskTrainingBatchJournalEntity(
    @PrimaryKey val batchId: String,
    val profileKey: String,
    val modelTask: String,
    val expectedTrainingRevision: Long,
    val selectedRowIds: String,
    val state: String,
    val createdAtEpochMs: Long,
    val committedAtEpochMs: Long?
)

@Entity(
    tableName = "preset_training_sample",
    indices = [
        Index(value = ["profileKey", "domainId", "opportunityId", "candidateId"], unique = true),
        Index(value = ["profileKey", "domainId", "label"])
    ]
)
data class PresetTrainingSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileKey: String,
    val domainId: String,
    val opportunityId: String,
    val candidateId: String,
    val features: ByteArray,
    val label: Boolean,
    val occurredEpochDay: Long,
    val trainingCount: Int,
    val labelSource: String,
    val sampleWeight: Float,
    val feedbackSource: String,
    val weightConfigVersion: Int,
    val naturalHoldoutEligible: Boolean,
    val interactionId: String?,
    val candidateOrdinal: Int? = null
)

@Entity(
    tableName = "preset_shadow_evaluation",
    indices = [Index(value = ["profileKey", "domainId", "opportunityId", "candidateId"], unique = true)]
)
data class PresetShadowEvaluationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileKey: String,
    val domainId: String,
    val opportunityId: String,
    val candidateId: String,
    val label: Boolean,
    val statScore: Float,
    val tinyScore: Float,
    val recentBaselineScore: Float,
    val frequencyBaselineScore: Float,
    val tinyCheckpointId: String,
    val occurredEpochDay: Long,
    val featureSchemaVersion: Int,
    val evaluationSource: String,
    val naturalHoldoutEligible: Boolean
)

@Entity(
    tableName = "bootstrap_training_consent",
    indices = [Index(value = ["participantId"], unique = true)]
)
data class BootstrapTrainingConsentEntity(
    @PrimaryKey val profileKey: String,
    val consentLifecycleId: String,
    val participantId: String,
    val secretAlias: String,
    val encryptedRevocationCapability: String,
    val consentSchemaVersion: Int,
    val includeHistorical: Boolean,
    val historicalBackfillCompleted: Boolean,
    val nextSequenceNo: Long,
    val contributedExampleCount: Long,
    val lastUploadAtEpochMs: Long?,
    val state: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "bootstrap_training_example",
    indices = [
        Index(value = ["profileKey", "exampleId"], unique = true),
        Index(value = ["participantId", "sequenceNo"], unique = true),
        Index(value = ["profileKey", "state", "sequenceNo"]),
        Index(value = ["profileKey", "task", "occurredEpochDay"]),
        Index(value = ["batchId"])
    ]
)
data class BootstrapTrainingExampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val exampleId: String,
    val profileKey: String,
    val consentLifecycleId: String,
    val participantId: String,
    val sequenceNo: Long,
    val task: String,
    val completeness: String,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val features: ByteArray,
    val availabilityMask: ByteArray?,
    val targetLabel: String,
    val feedbackSource: String,
    val sampleWeight: Float,
    val deliveryLane: String?,
    val domainId: String?,
    val opportunityGroupId: String?,
    val candidateOrdinal: Int?,
    val journeyLengthBucket: Int?,
    val naturalHoldoutEligible: Boolean,
    val occurredEpochDay: Long,
    val historical: Boolean,
    val state: String,
    val batchId: String?,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "bootstrap_training_batch",
    indices = [
        Index(value = ["profileKey", "state", "nextAttemptAtEpochMs"]),
        Index(value = ["participantId", "createdAtEpochMs"])
    ]
)
data class BootstrapTrainingBatchEntity(
    @PrimaryKey val batchId: String,
    val profileKey: String,
    val consentLifecycleId: String,
    val participantId: String,
    val protocolVersion: Int,
    val body: ByteArray,
    val bodySha256: String,
    val exampleCount: Int,
    val containsHistorical: Boolean,
    val state: String,
    val attemptCount: Int,
    val nextAttemptAtEpochMs: Long,
    val lastErrorCode: String?,
    val createdAtEpochMs: Long,
    val acknowledgedAtEpochMs: Long?
)

@Entity(
    tableName = "bootstrap_training_deletion_tombstone",
    indices = [Index(value = ["state", "nextAttemptAtEpochMs"])]
)
data class BootstrapTrainingDeletionTombstoneEntity(
    @PrimaryKey val deletionId: String,
    val participantId: String,
    val consentLifecycleId: String,
    val secretAlias: String,
    val encryptedRevocationCapability: String,
    val state: String,
    val attemptCount: Int,
    val nextAttemptAtEpochMs: Long,
    val lastErrorCode: String?,
    val createdAtEpochMs: Long,
    val acknowledgedAtEpochMs: Long?
)
