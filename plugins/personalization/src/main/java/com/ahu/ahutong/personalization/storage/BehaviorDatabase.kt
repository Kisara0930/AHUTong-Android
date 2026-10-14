package com.ahu.ahutong.personalization.storage

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction

const val BEHAVIOR_DATABASE_VERSION = 5

@Database(
    entities = [
        BehaviorEventEntity::class,
        PendingPredictionEntity::class,
        ProductExecutionLeaseEntity::class,
        ActionStatEntity::class,
        TrainingSampleEntity::class,
        TrainingBatchJournalEntity::class,
        ShadowEvaluationEntity::class,
        CandidateShadowEvaluationEntity::class,
        TinyPromotionStateEntity::class,
        TinyRuntimeHealthStateEntity::class,
        PromotionEvaluationWindowEntity::class,
        PromotionActionQualificationEntity::class,
        PromotionTransitionJournalEntity::class,
        LearningStateEntity::class,
        TelemetryReportEntity::class,
        TelemetryStateEntity::class,
        TelemetryAggregateWindowEntity::class,
        TelemetryV3AggregateWindowEntity::class,
        TelemetryDeletionTombstoneEntity::class,
        SemanticEventEntity::class,
        SemanticChangeSetEntity::class,
        PendingJourneyEntity::class,
        JourneyActionStatEntity::class,
        JourneyTrainingSampleEntity::class,
        JourneyShadowEvaluationEntity::class,
        LocalParameterPresetEntity::class,
        PresetUsageStatEntity::class,
        TargetedPredictionFeedbackEntity::class,
        PresetRecommendationInteractionEntity::class,
        TaskModelStateEntity::class,
        TaskTrainingBatchJournalEntity::class,
        PresetTrainingSampleEntity::class,
        PresetShadowEvaluationEntity::class,
        BootstrapTrainingConsentEntity::class,
        BootstrapTrainingExampleEntity::class,
        BootstrapTrainingBatchEntity::class,
        BootstrapTrainingDeletionTombstoneEntity::class
    ],
    version = BEHAVIOR_DATABASE_VERSION,
    exportSchema = true
)
abstract class BehaviorDatabase : RoomDatabase() {
    abstract fun behaviorDao(): BehaviorDao
}


suspend inline fun <T> BehaviorDatabase.transaction(crossinline block: suspend () -> T): T =
    withTransaction { block() }
