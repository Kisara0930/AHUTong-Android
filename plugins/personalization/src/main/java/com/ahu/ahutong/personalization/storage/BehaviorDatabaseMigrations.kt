package com.ahu.ahutong.personalization.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object BehaviorDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements.forEach(db::execSQL)
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            telemetryV3Statements.forEach(db::execSQL)
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            bootstrapTrainingStatements.forEach(db::execSQL)
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `training_sample` ADD COLUMN `deliveryLane` TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE `training_sample` ADD COLUMN `naturalHoldoutEligible` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE `training_sample` SET `deliveryLane` = 'ORDINARY_NEXT_ACTION', `naturalHoldoutEligible` = 1 " +
                    "WHERE `labelSource` IN ('ORGANIC_ACTION', 'INTERVENTION_FREE_TIMEOUT')"
            )
            db.execSQL("ALTER TABLE `preset_recommendation_interaction` ADD COLUMN `exposureFeatures` BLOB")
            db.execSQL("ALTER TABLE `preset_recommendation_interaction` ADD COLUMN `candidateOrdinal` INTEGER")
            db.execSQL("ALTER TABLE `preset_recommendation_interaction` ADD COLUMN `occurredEpochDay` INTEGER")
            db.execSQL("ALTER TABLE `preset_training_sample` ADD COLUMN `candidateOrdinal` INTEGER")
            db.execSQL(
                "DELETE FROM `bootstrap_training_example` WHERE `rowId` NOT IN (" +
                    "SELECT MIN(`rowId`) FROM `bootstrap_training_example` GROUP BY `participantId`, `sequenceNo`)"
            )
            db.execSQL(
                "UPDATE `bootstrap_training_consent` SET `nextSequenceNo` = MAX(`nextSequenceNo`, " +
                    "COALESCE((SELECT MAX(e.`sequenceNo`) + 1 FROM `bootstrap_training_example` e " +
                    "WHERE e.`participantId` = `bootstrap_training_consent`.`participantId`), 1))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_bootstrap_training_example_participantId_sequenceNo` " +
                    "ON `bootstrap_training_example` (`participantId`, `sequenceNo`)"
            )
        }
    }

    private val statements = listOf(
        """CREATE TABLE IF NOT EXISTS `semantic_event` (`eventId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `sequenceNo` INTEGER NOT NULL, `eventFamily` TEXT NOT NULL, `domainId` TEXT NOT NULL, `semanticId` TEXT NOT NULL, `changeKind` TEXT NOT NULL, `coarseValueBucket` TEXT NOT NULL, `route` TEXT, `affectedCandidateSetVersion` INTEGER NOT NULL, `source` TEXT NOT NULL, `committedAtEpochDay` INTEGER NOT NULL, `occurredAtElapsedMs` INTEGER NOT NULL, `semanticSchemaVersion` INTEGER NOT NULL, `tainted` INTEGER NOT NULL, `mutationBatchId` TEXT NOT NULL, `changeSetId` TEXT NOT NULL, PRIMARY KEY(`eventId`))""",
        "CREATE INDEX IF NOT EXISTS `index_semantic_event_profileKey_sequenceNo` ON `semantic_event` (`profileKey`, `sequenceNo`)",
        "CREATE INDEX IF NOT EXISTS `index_semantic_event_profileKey_semanticId_committedAtEpochDay` ON `semantic_event` (`profileKey`, `semanticId`, `committedAtEpochDay`)",
        "CREATE INDEX IF NOT EXISTS `index_semantic_event_profileKey_mutationBatchId` ON `semantic_event` (`profileKey`, `mutationBatchId`)",
        """CREATE TABLE IF NOT EXISTS `semantic_change_set` (`changeSetId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `route` TEXT, `mutationBatchId` TEXT NOT NULL, `firstOccurredAtElapsedMs` INTEGER NOT NULL, `lastOccurredAtElapsedMs` INTEGER NOT NULL, `mutationCount` INTEGER NOT NULL, `semanticIdsCsv` TEXT NOT NULL, `affectedActionIdsCsv` TEXT NOT NULL, `affectedCandidateSetVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`changeSetId`))""",
        "CREATE INDEX IF NOT EXISTS `index_semantic_change_set_profileKey_sessionId_state_lastOccurredAtElapsedMs` ON `semantic_change_set` (`profileKey`, `sessionId`, `state`, `lastOccurredAtElapsedMs`)",
        """CREATE TABLE IF NOT EXISTS `pending_journey` (`journeyId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `processInstanceId` TEXT NOT NULL, `triggerEventId` TEXT NOT NULL, `sequenceNo` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `createdAtElapsedMs` INTEGER NOT NULL, `deadlineElapsedMs` INTEGER NOT NULL, `maximumActions` INTEGER NOT NULL, `observedActionCount` INTEGER NOT NULL, `observedActionIdsCsv` TEXT NOT NULL, `lastLeafEnteredAtElapsedMs` INTEGER, `lastLeafActionId` TEXT, `lastLeafEventId` TEXT, `featureSchemaVersion` INTEGER NOT NULL, `journeyOutputSchemaVersion` INTEGER NOT NULL, `features` BLOB NOT NULL, `inputDigest` TEXT NOT NULL, `contextSnapshotJson` TEXT NOT NULL, `statProbabilities` BLOB NOT NULL, `tinyProbabilities` BLOB, `statModelVersion` INTEGER NOT NULL, `tinyModelVersion` INTEGER, `statInferenceNanos` INTEGER NOT NULL, `tinyInferenceNanos` INTEGER, `activeCheckpointId` TEXT, `candidateCheckpointId` TEXT, `stageAtDecision` TEXT NOT NULL, `mixedLambda` REAL NOT NULL, `isPromotionHoldout` INTEGER NOT NULL, `interventionState` TEXT NOT NULL, `resolutionStatus` TEXT NOT NULL, `censorReason` TEXT, `finalTargetActionId` TEXT, `consumedTerminalEventId` TEXT, PRIMARY KEY(`journeyId`))""",
        "CREATE INDEX IF NOT EXISTS `index_pending_journey_profileKey_resolutionStatus_deadlineElapsedMs` ON `pending_journey` (`profileKey`, `resolutionStatus`, `deadlineElapsedMs`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_journey_profileKey_sessionId_triggerEventId` ON `pending_journey` (`profileKey`, `sessionId`, `triggerEventId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_journey_profileKey_consumedTerminalEventId` ON `pending_journey` (`profileKey`, `consumedTerminalEventId`)",
        """CREATE TABLE IF NOT EXISTS `journey_action_stat` (`profileKey` TEXT NOT NULL, `contextKey` TEXT NOT NULL, `targetActionId` TEXT NOT NULL, `positiveMass` REAL NOT NULL, `exposureMass` REAL NOT NULL, `updatedAtEpochDay` INTEGER NOT NULL, PRIMARY KEY(`profileKey`, `contextKey`, `targetActionId`))""",
        "CREATE INDEX IF NOT EXISTS `index_journey_action_stat_profileKey_targetActionId` ON `journey_action_stat` (`profileKey`, `targetActionId`)",
        """CREATE TABLE IF NOT EXISTS `journey_training_sample` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sampleId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `journeyId` TEXT NOT NULL, `featureSchemaVersion` INTEGER NOT NULL, `journeyOutputSchemaVersion` INTEGER NOT NULL, `features` BLOB NOT NULL, `targetIndex` INTEGER NOT NULL, `targetActionId` TEXT NOT NULL, `targetFamily` TEXT NOT NULL, `journeyLength` INTEGER NOT NULL, `occurredEpochDay` INTEGER NOT NULL, `replayPriority` REAL NOT NULL, `trainingCount` INTEGER NOT NULL, `labelSource` TEXT NOT NULL)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_journey_training_sample_profileKey_journeyId` ON `journey_training_sample` (`profileKey`, `journeyId`)",
        "CREATE INDEX IF NOT EXISTS `index_journey_training_sample_profileKey_targetIndex` ON `journey_training_sample` (`profileKey`, `targetIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_journey_training_sample_profileKey_occurredEpochDay` ON `journey_training_sample` (`profileKey`, `occurredEpochDay`)",
        """CREATE TABLE IF NOT EXISTS `journey_shadow_evaluation` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileKey` TEXT NOT NULL, `evaluationSeq` INTEGER NOT NULL, `journeyId` TEXT NOT NULL, `occurredEpochDay` INTEGER NOT NULL, `trueLabel` TEXT NOT NULL, `journeyLength` INTEGER NOT NULL, `statTop1` INTEGER NOT NULL, `statTop3` INTEGER NOT NULL, `statReciprocalRank` REAL NOT NULL, `statBrier` REAL NOT NULL, `statLogLoss` REAL NOT NULL, `tinyTop1` INTEGER NOT NULL, `tinyTop3` INTEGER NOT NULL, `tinyReciprocalRank` REAL NOT NULL, `tinyBrier` REAL NOT NULL, `tinyLogLoss` REAL NOT NULL, `tinyTop1Confidence` REAL NOT NULL, `tinyCheckpointId` TEXT, `statInferenceNanos` INTEGER NOT NULL, `tinyInferenceNanos` INTEGER NOT NULL, `stage` TEXT NOT NULL, `featureSchemaVersion` INTEGER NOT NULL, `journeyOutputSchemaVersion` INTEGER NOT NULL)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_journey_shadow_evaluation_profileKey_evaluationSeq` ON `journey_shadow_evaluation` (`profileKey`, `evaluationSeq`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_journey_shadow_evaluation_profileKey_journeyId` ON `journey_shadow_evaluation` (`profileKey`, `journeyId`)",
        """CREATE TABLE IF NOT EXISTS `local_parameter_preset` (`presetId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `domainId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `localPayloadJson` TEXT NOT NULL, `coarseFeaturesJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `lastOrganicUsedAtEpochMs` INTEGER, `organicUseCount` INTEGER NOT NULL, `source` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, PRIMARY KEY(`presetId`))""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_parameter_preset_profileKey_domainId_fingerprint` ON `local_parameter_preset` (`profileKey`, `domainId`, `fingerprint`)",
        "CREATE INDEX IF NOT EXISTS `index_local_parameter_preset_profileKey_domainId_lastOrganicUsedAtEpochMs` ON `local_parameter_preset` (`profileKey`, `domainId`, `lastOrganicUsedAtEpochMs`)",
        """CREATE TABLE IF NOT EXISTS `preset_usage_stat` (`profileKey` TEXT NOT NULL, `domainId` TEXT NOT NULL, `contextKey` TEXT NOT NULL, `presetId` TEXT NOT NULL, `positiveMass` REAL NOT NULL, `exposureMass` REAL NOT NULL, `updatedAtEpochDay` INTEGER NOT NULL, PRIMARY KEY(`profileKey`, `domainId`, `contextKey`, `presetId`))""",
        "CREATE INDEX IF NOT EXISTS `index_preset_usage_stat_profileKey_domainId_presetId` ON `preset_usage_stat` (`profileKey`, `domainId`, `presetId`)",
        """CREATE TABLE IF NOT EXISTS `targeted_prediction_feedback` (`feedbackId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `predictionTask` TEXT NOT NULL, `decisionId` TEXT NOT NULL, `candidateId` TEXT, `feedbackType` TEXT NOT NULL, `isolatedFromTraining` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`feedbackId`))""",
        "CREATE INDEX IF NOT EXISTS `index_targeted_prediction_feedback_profileKey_predictionTask_createdAtEpochMs` ON `targeted_prediction_feedback` (`profileKey`, `predictionTask`, `createdAtEpochMs`)",
        """CREATE TABLE IF NOT EXISTS `preset_recommendation_interaction` (`interactionId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `domainId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, `candidateFingerprint` TEXT NOT NULL, `state` TEXT NOT NULL, `shownAtEpochMs` INTEGER NOT NULL, `appliedAtEpochMs` INTEGER, `resolvedAtEpochMs` INTEGER, `resolutionFingerprint` TEXT, `feedbackWeight` REAL, `checkpointId` TEXT, PRIMARY KEY(`interactionId`))""",
        "CREATE INDEX IF NOT EXISTS `index_preset_recommendation_interaction_profileKey_domainId_state_shownAtEpochMs` ON `preset_recommendation_interaction` (`profileKey`, `domainId`, `state`, `shownAtEpochMs`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_preset_recommendation_interaction_profileKey_opportunityId_candidateId` ON `preset_recommendation_interaction` (`profileKey`, `opportunityId`, `candidateId`)",
        """CREATE TABLE IF NOT EXISTS `task_model_state` (`profileKey` TEXT NOT NULL, `modelTask` TEXT NOT NULL, `modelGeneration` INTEGER NOT NULL, `featureSchemaVersion` INTEGER NOT NULL, `outputSchemaVersion` INTEGER NOT NULL, `activeCheckpointId` TEXT, `candidateCheckpointId` TEXT, `trainingRevision` INTEGER NOT NULL, `stage` TEXT NOT NULL, `mixedLambda` REAL NOT NULL, `consecutivePassingWindows` INTEGER NOT NULL, `consecutiveFailingWindows` INTEGER NOT NULL, `validSampleCount` INTEGER NOT NULL, `nonNoneSampleCount` INTEGER NOT NULL, `targetFamilyCount` INTEGER NOT NULL, `ece` REAL NOT NULL, `healthState` TEXT NOT NULL, `lastTransitionReason` TEXT NOT NULL, `lastEvaluationSeq` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`profileKey`, `modelTask`))""",
        "CREATE INDEX IF NOT EXISTS `index_task_model_state_profileKey_stage` ON `task_model_state` (`profileKey`, `stage`)",
        """CREATE TABLE IF NOT EXISTS `task_training_batch_journal` (`batchId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `modelTask` TEXT NOT NULL, `expectedTrainingRevision` INTEGER NOT NULL, `selectedRowIds` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `committedAtEpochMs` INTEGER, PRIMARY KEY(`batchId`))""",
        "CREATE INDEX IF NOT EXISTS `index_task_training_batch_journal_profileKey_modelTask_state_createdAtEpochMs` ON `task_training_batch_journal` (`profileKey`, `modelTask`, `state`, `createdAtEpochMs`)",
        """CREATE TABLE IF NOT EXISTS `preset_training_sample` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileKey` TEXT NOT NULL, `domainId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, `features` BLOB NOT NULL, `label` INTEGER NOT NULL, `occurredEpochDay` INTEGER NOT NULL, `trainingCount` INTEGER NOT NULL, `labelSource` TEXT NOT NULL, `sampleWeight` REAL NOT NULL, `feedbackSource` TEXT NOT NULL, `weightConfigVersion` INTEGER NOT NULL, `naturalHoldoutEligible` INTEGER NOT NULL, `interactionId` TEXT)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_preset_training_sample_profileKey_domainId_opportunityId_candidateId` ON `preset_training_sample` (`profileKey`, `domainId`, `opportunityId`, `candidateId`)",
        "CREATE INDEX IF NOT EXISTS `index_preset_training_sample_profileKey_domainId_label` ON `preset_training_sample` (`profileKey`, `domainId`, `label`)",
        """CREATE TABLE IF NOT EXISTS `preset_shadow_evaluation` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileKey` TEXT NOT NULL, `domainId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, `label` INTEGER NOT NULL, `statScore` REAL NOT NULL, `tinyScore` REAL NOT NULL, `recentBaselineScore` REAL NOT NULL, `frequencyBaselineScore` REAL NOT NULL, `tinyCheckpointId` TEXT NOT NULL, `occurredEpochDay` INTEGER NOT NULL, `featureSchemaVersion` INTEGER NOT NULL, `evaluationSource` TEXT NOT NULL, `naturalHoldoutEligible` INTEGER NOT NULL)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_preset_shadow_evaluation_profileKey_domainId_opportunityId_candidateId` ON `preset_shadow_evaluation` (`profileKey`, `domainId`, `opportunityId`, `candidateId`)"
    )

    private val telemetryV3Statements = listOf(
        "ALTER TABLE `pending_prediction` ADD COLUMN `effectiveProbabilities` BLOB",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `effectiveTop1` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `effectiveTop3` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `effectiveReciprocalRank` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `effectiveBrier` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `effectiveLogLoss` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `effectiveTop1Confidence` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `recentTop1` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `recentBrier` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `recentLogLoss` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `recentTop1Confidence` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `timeTop1` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `timeBrier` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `timeLogLoss` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `shadow_evaluation` ADD COLUMN `timeTop1Confidence` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `effectiveTop1` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `statTop1Confidence` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `effectiveTop3` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `effectiveReciprocalRank` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `effectiveBrier` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `effectiveLogLoss` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `effectiveTop1Confidence` REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `tinyAvailable` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `journey_shadow_evaluation` ADD COLUMN `promotionEligible` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `telemetry_report` ADD COLUMN `schemaVersion` INTEGER NOT NULL DEFAULT 2",
        """CREATE TABLE IF NOT EXISTS `telemetry_v3_aggregate_window` (`windowId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `consentLifecycleId` TEXT NOT NULL, `telemetryId` TEXT NOT NULL, `modelGenerationId` TEXT NOT NULL, `task` TEXT NOT NULL, `windowStartEpochDay` INTEGER NOT NULL, `windowEndEpochDay` INTEGER NOT NULL, `sampleCount` INTEGER NOT NULL, `naturalHoldoutSampleCount` INTEGER NOT NULL, `aggregateJson` TEXT NOT NULL, `appVersionCode` INTEGER NOT NULL, `featureSchemaVersion` INTEGER NOT NULL, `outputSchemaVersion` INTEGER NOT NULL, `metricSchemaVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`windowId`))""",
        "CREATE INDEX IF NOT EXISTS `index_telemetry_v3_aggregate_window_profileKey_consentLifecycleId_task_state_createdAtEpochMs` ON `telemetry_v3_aggregate_window` (`profileKey`, `consentLifecycleId`, `task`, `state`, `createdAtEpochMs`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_telemetry_v3_aggregate_window_windowId` ON `telemetry_v3_aggregate_window` (`windowId`)"
    )

    private val bootstrapTrainingStatements = listOf(
        """CREATE TABLE IF NOT EXISTS `bootstrap_training_consent` (`profileKey` TEXT NOT NULL, `consentLifecycleId` TEXT NOT NULL, `participantId` TEXT NOT NULL, `secretAlias` TEXT NOT NULL, `encryptedRevocationCapability` TEXT NOT NULL, `consentSchemaVersion` INTEGER NOT NULL, `includeHistorical` INTEGER NOT NULL, `historicalBackfillCompleted` INTEGER NOT NULL, `nextSequenceNo` INTEGER NOT NULL, `contributedExampleCount` INTEGER NOT NULL, `lastUploadAtEpochMs` INTEGER, `state` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`profileKey`))""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bootstrap_training_consent_participantId` ON `bootstrap_training_consent` (`participantId`)",
        """CREATE TABLE IF NOT EXISTS `bootstrap_training_example` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exampleId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `consentLifecycleId` TEXT NOT NULL, `participantId` TEXT NOT NULL, `sequenceNo` INTEGER NOT NULL, `task` TEXT NOT NULL, `completeness` TEXT NOT NULL, `featureSchemaVersion` INTEGER NOT NULL, `outputSchemaVersion` INTEGER NOT NULL, `actionCatalogVersion` INTEGER NOT NULL, `features` BLOB NOT NULL, `availabilityMask` BLOB, `targetLabel` TEXT NOT NULL, `feedbackSource` TEXT NOT NULL, `sampleWeight` REAL NOT NULL, `deliveryLane` TEXT, `domainId` TEXT, `opportunityGroupId` TEXT, `candidateOrdinal` INTEGER, `journeyLengthBucket` INTEGER, `naturalHoldoutEligible` INTEGER NOT NULL, `occurredEpochDay` INTEGER NOT NULL, `historical` INTEGER NOT NULL, `state` TEXT NOT NULL, `batchId` TEXT, `createdAtEpochMs` INTEGER NOT NULL)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bootstrap_training_example_profileKey_exampleId` ON `bootstrap_training_example` (`profileKey`, `exampleId`)",
        "CREATE INDEX IF NOT EXISTS `index_bootstrap_training_example_profileKey_state_sequenceNo` ON `bootstrap_training_example` (`profileKey`, `state`, `sequenceNo`)",
        "CREATE INDEX IF NOT EXISTS `index_bootstrap_training_example_profileKey_task_occurredEpochDay` ON `bootstrap_training_example` (`profileKey`, `task`, `occurredEpochDay`)",
        "CREATE INDEX IF NOT EXISTS `index_bootstrap_training_example_batchId` ON `bootstrap_training_example` (`batchId`)",
        """CREATE TABLE IF NOT EXISTS `bootstrap_training_batch` (`batchId` TEXT NOT NULL, `profileKey` TEXT NOT NULL, `consentLifecycleId` TEXT NOT NULL, `participantId` TEXT NOT NULL, `protocolVersion` INTEGER NOT NULL, `body` BLOB NOT NULL, `bodySha256` TEXT NOT NULL, `exampleCount` INTEGER NOT NULL, `containsHistorical` INTEGER NOT NULL, `state` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAtEpochMs` INTEGER NOT NULL, `lastErrorCode` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `acknowledgedAtEpochMs` INTEGER, PRIMARY KEY(`batchId`))""",
        "CREATE INDEX IF NOT EXISTS `index_bootstrap_training_batch_profileKey_state_nextAttemptAtEpochMs` ON `bootstrap_training_batch` (`profileKey`, `state`, `nextAttemptAtEpochMs`)",
        "CREATE INDEX IF NOT EXISTS `index_bootstrap_training_batch_participantId_createdAtEpochMs` ON `bootstrap_training_batch` (`participantId`, `createdAtEpochMs`)",
        """CREATE TABLE IF NOT EXISTS `bootstrap_training_deletion_tombstone` (`deletionId` TEXT NOT NULL, `participantId` TEXT NOT NULL, `consentLifecycleId` TEXT NOT NULL, `secretAlias` TEXT NOT NULL, `encryptedRevocationCapability` TEXT NOT NULL, `state` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAtEpochMs` INTEGER NOT NULL, `lastErrorCode` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `acknowledgedAtEpochMs` INTEGER, PRIMARY KEY(`deletionId`))""",
        "CREATE INDEX IF NOT EXISTS `index_bootstrap_training_deletion_tombstone_state_nextAttemptAtEpochMs` ON `bootstrap_training_deletion_tombstone` (`state`, `nextAttemptAtEpochMs`)"
    )
}
