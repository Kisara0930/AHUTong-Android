package com.ahu.ahutong.personalization.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
abstract class BehaviorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEvent(event: BehaviorEventEntity)

    @Query("SELECT * FROM behavior_event WHERE profileKey = :profileKey ORDER BY sequenceNo DESC LIMIT :limit")
    abstract suspend fun recentEvents(profileKey: String, limit: Int): List<BehaviorEventEntity>

    @Query("SELECT COALESCE(MAX(sequenceNo), 0) FROM behavior_event WHERE profileKey = :profileKey")
    abstract suspend fun maxEventSequence(profileKey: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPending(pending: PendingPredictionEntity)

    @Update
    abstract suspend fun updatePending(pending: PendingPredictionEntity)

    @Query("UPDATE pending_prediction SET resolutionStatus = :terminalStatus, finalOrganicTarget = :targetOutputId, resolvedByEventId = :resolvedByEventId WHERE decisionId = :decisionId AND profileKey = :profileKey AND resolutionStatus = 'PENDING' AND preparationState = 'PENDING' AND interventionState = 'NONE'")
    abstract suspend fun resolvePendingCas(
        decisionId: String,
        profileKey: String,
        terminalStatus: String,
        targetOutputId: String?,
        resolvedByEventId: String?
    ): Int

    @Query("UPDATE pending_prediction SET resolutionStatus = :terminalStatus, resolvedByEventId = :resolvedByEventId WHERE decisionId = :decisionId AND profileKey = :profileKey AND resolutionStatus = 'PENDING'")
    abstract suspend fun censorPendingCas(
        decisionId: String,
        profileKey: String,
        terminalStatus: String,
        resolvedByEventId: String? = null
    ): Int

    @Query("UPDATE pending_prediction SET interventionState = :interventionState, resolutionStatus = :terminalStatus, resolvedByEventId = :resolvedByEventId WHERE decisionId = :decisionId AND profileKey = :profileKey AND resolutionStatus = 'PENDING'")
    abstract suspend fun invalidatePendingForObservedSourceCas(
        decisionId: String,
        profileKey: String,
        interventionState: String,
        terminalStatus: String,
        resolvedByEventId: String
    ): Int

    @Query("UPDATE pending_prediction SET interventionState = :interventionState, resolutionStatus = 'INVALIDATED_INTERVENTION_PREPARED' WHERE decisionId = :decisionId AND profileKey = :profileKey AND resolutionStatus = 'PENDING' AND preparationState = 'PENDING' AND interventionState IN ('NONE', 'TAINTED_CHAIN')")
    abstract suspend fun prepareInterventionCas(
        decisionId: String,
        profileKey: String,
        interventionState: String
    ): Int

    @Query("UPDATE pending_prediction SET interventionState = :interventionState, resolutionStatus = 'INVALIDATED_INTERVENTION_PREPARED' WHERE decisionId = :decisionId AND profileKey = :profileKey AND resolutionStatus = 'PENDING' AND preparationState IN ('PREPARING', 'PENDING') AND interventionState IN ('NONE', 'TAINTED_CHAIN')")
    abstract suspend fun prepareEarlyTargetedInterventionCas(
        decisionId: String,
        profileKey: String,
        interventionState: String
    ): Int

    @Transaction
    open suspend fun prepareProductExecution(
        decisionId: String,
        profileKey: String,
        interventionState: String,
        lease: ProductExecutionLeaseEntity,
        allowPreparing: Boolean = false
    ): Boolean {
        val prepared = if (allowPreparing) {
            prepareEarlyTargetedInterventionCas(decisionId, profileKey, interventionState)
        } else {
            prepareInterventionCas(decisionId, profileKey, interventionState)
        }
        if (prepared != 1) return false
        insertLease(lease)
        return true
    }

    @Query("SELECT * FROM pending_prediction WHERE decisionId = :decisionId")
    abstract suspend fun pending(decisionId: String): PendingPredictionEntity?

    @Query("SELECT * FROM pending_prediction WHERE profileKey = :profileKey AND resolutionStatus = 'PENDING' ORDER BY sequenceNo DESC LIMIT 1")
    abstract suspend fun latestPending(profileKey: String): PendingPredictionEntity?

    @Query("SELECT * FROM pending_prediction WHERE profileKey = :profileKey AND resolutionStatus = 'PENDING' AND processInstanceId = :processInstanceId AND labelDeadlineElapsedMs <= :elapsedMs")
    abstract suspend fun expiredPending(profileKey: String, processInstanceId: String, elapsedMs: Long): List<PendingPredictionEntity>

    @Query("UPDATE pending_prediction SET resolutionStatus = 'CENSORED_PROCESS_RESTART' WHERE profileKey = :profileKey AND resolutionStatus = 'PENDING' AND processInstanceId != :processInstanceId")
    abstract suspend fun censorStaleProcessPending(profileKey: String, processInstanceId: String): Int

    @Query(
        """
        UPDATE pending_prediction
        SET resolutionStatus = 'CENSORED_ORPHANED_RESOLUTION_EVENT',
            resolvedByEventId = (
                SELECT eventId FROM behavior_event
                WHERE behavior_event.resolvedDecisionId = pending_prediction.decisionId
                LIMIT 1
            )
        WHERE profileKey = :profileKey
          AND resolutionStatus = 'PENDING'
          AND EXISTS (
              SELECT 1 FROM behavior_event
              WHERE behavior_event.resolvedDecisionId = pending_prediction.decisionId
          )
        """
    )
    abstract suspend fun censorPendingWithExistingResolutionEvent(profileKey: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertActionStat(value: ActionStatEntity)

    @Query("SELECT * FROM action_stat WHERE profileKey = :profileKey")
    abstract suspend fun actionStats(profileKey: String): List<ActionStatEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrainingSample(sample: TrainingSampleEntity): Long

    @Query("SELECT COUNT(*) FROM training_sample WHERE profileKey = :profileKey")
    abstract suspend fun trainingSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM training_sample WHERE profileKey = :profileKey AND labelSource IN ('ORGANIC_ACTION', 'INTERVENTION_FREE_TIMEOUT')")
    abstract suspend fun naturalTrainingSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM training_sample WHERE profileKey = :profileKey AND labelSource = 'SUGGESTION_ACCEPTED'")
    abstract suspend fun suggestionAcceptedTrainingSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM training_sample WHERE profileKey = :profileKey AND labelSource IN ('ORGANIC_ACTION', 'INTERVENTION_FREE_TIMEOUT') AND targetActionId != 'NONE'")
    abstract suspend fun organicNonNoneTrainingSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(DISTINCT actionFamily) FROM training_sample WHERE profileKey = :profileKey AND labelSource IN ('ORGANIC_ACTION', 'INTERVENTION_FREE_TIMEOUT') AND targetActionId != 'NONE'")
    abstract suspend fun trainingActionFamilyCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM (SELECT targetActionId FROM training_sample WHERE profileKey = :profileKey AND labelSource IN ('ORGANIC_ACTION', 'INTERVENTION_FREE_TIMEOUT') AND targetActionId != 'NONE' GROUP BY targetActionId HAVING COUNT(*) >= :minimumPerAction)")
    abstract suspend fun qualifiedTrainingActionCount(profileKey: String, minimumPerAction: Int): Int

    @Query("SELECT DISTINCT targetActionId FROM training_sample WHERE profileKey = :profileKey AND labelSource = 'ORGANIC_ACTION' AND targetActionId != 'NONE'")
    abstract suspend fun organicNonNoneTrainingActionIds(profileKey: String): List<String>

    @Query("SELECT * FROM training_sample WHERE profileKey = :profileKey ORDER BY rowId DESC LIMIT :limit")
    abstract suspend fun recentTrainingSamples(profileKey: String, limit: Int): List<TrainingSampleEntity>

    @Query("SELECT * FROM training_sample WHERE profileKey = :profileKey ORDER BY trainingCount ASC, replayPriority DESC, rowId ASC LIMIT :limit")
    abstract suspend fun historicalReplayCandidates(profileKey: String, limit: Int): List<TrainingSampleEntity>

    @Query("UPDATE training_sample SET trainingCount = trainingCount + 1 WHERE rowId IN (:rowIds)")
    abstract suspend fun incrementTrainingCounts(rowIds: List<Long>)

    @Query("SELECT * FROM training_sample WHERE profileKey = :profileKey AND rowId IN (:rowIds)")
    abstract suspend fun trainingSamplesByIds(profileKey: String, rowIds: List<Long>): List<TrainingSampleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrainingBatchJournal(value: TrainingBatchJournalEntity): Long

    @Query("SELECT * FROM training_batch_journal WHERE profileKey = :profileKey AND state = 'PREPARED' ORDER BY createdAtEpochMs LIMIT 1")
    abstract suspend fun preparedTrainingBatch(profileKey: String): TrainingBatchJournalEntity?

    @Query("UPDATE training_batch_journal SET state = 'COMMITTED', committedAtEpochMs = :committedAt WHERE batchId = :batchId AND state = 'PREPARED'")
    abstract suspend fun commitTrainingBatchJournal(batchId: String, committedAt: Long): Int

    @Query("UPDATE training_batch_journal SET state = 'ABANDONED', committedAtEpochMs = :committedAt WHERE batchId = :batchId AND state = 'PREPARED'")
    abstract suspend fun abandonTrainingBatchJournal(batchId: String, committedAt: Long): Int

    @Transaction
    open suspend fun completeTrainingBatch(batchId: String, rowIds: List<Long>, committedAt: Long) {
        check(commitTrainingBatchJournal(batchId, committedAt) == 1) { "training batch journal was not prepared" }
        incrementTrainingCounts(rowIds)
    }

    @Query("DELETE FROM training_sample WHERE profileKey = :profileKey AND rowId NOT IN (SELECT rowId FROM training_sample WHERE profileKey = :profileKey ORDER BY rowId DESC LIMIT :limit)")
    abstract suspend fun trimTrainingSamples(profileKey: String, limit: Int)

    @Query("DELETE FROM training_sample WHERE rowId = (SELECT sample.rowId FROM training_sample AS sample WHERE sample.profileKey = :profileKey ORDER BY (SELECT COUNT(*) FROM training_sample AS grouped WHERE grouped.profileKey = :profileKey AND grouped.targetActionId = sample.targetActionId) DESC, CASE WHEN sample.targetActionId = 'NONE' THEN 1 ELSE 0 END DESC, sample.trainingCount DESC, sample.replayPriority ASC, sample.rowId ASC LIMIT 1)")
    abstract suspend fun evictOneReplaySample(profileKey: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertShadowEvaluation(value: ShadowEvaluationEntity)

    @Query("SELECT COALESCE(MAX(evaluationSeq), 0) FROM shadow_evaluation WHERE profileKey = :profileKey")
    abstract suspend fun maxEvaluationSeq(profileKey: String): Long

    @Query("SELECT * FROM shadow_evaluation WHERE profileKey = :profileKey AND evaluationSeq > :afterSeq AND promotionEligible = 1 ORDER BY evaluationSeq LIMIT :limit")
    abstract suspend fun promotionEvaluations(profileKey: String, afterSeq: Long, limit: Int): List<ShadowEvaluationEntity>

    @Query("SELECT * FROM shadow_evaluation WHERE profileKey = :profileKey AND evaluationSeq > :afterSeq AND telemetryEligible = 1 ORDER BY evaluationSeq LIMIT :limit")
    abstract suspend fun telemetryEvaluations(profileKey: String, afterSeq: Long, limit: Int): List<ShadowEvaluationEntity>

    @Query("DELETE FROM shadow_evaluation WHERE profileKey = :profileKey AND rowId NOT IN (SELECT rowId FROM shadow_evaluation WHERE profileKey = :profileKey ORDER BY evaluationSeq DESC LIMIT :limit)")
    abstract suspend fun trimShadowEvaluations(profileKey: String, limit: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCandidateEvaluation(value: CandidateShadowEvaluationEntity)

    @Query("SELECT * FROM candidate_shadow_evaluation WHERE profileKey = :profileKey AND candidateCheckpointId = :candidateId AND consumed = 0 ORDER BY evaluationSeq")
    abstract suspend fun candidateEvaluations(profileKey: String, candidateId: String): List<CandidateShadowEvaluationEntity>

    @Query("UPDATE candidate_shadow_evaluation SET consumed = 1 WHERE profileKey = :profileKey AND candidateCheckpointId = :candidateId")
    abstract suspend fun consumeCandidateEvaluations(profileKey: String, candidateId: String)

    @Query("DELETE FROM candidate_shadow_evaluation WHERE profileKey = :profileKey AND consumed = 1")
    abstract suspend fun deleteConsumedCandidateEvaluations(profileKey: String)

    @Query("DELETE FROM candidate_shadow_evaluation WHERE profileKey = :profileKey AND candidateCheckpointId != :activeCandidateId")
    abstract suspend fun deleteStaleCandidateEvaluations(profileKey: String, activeCandidateId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPromotionState(value: TinyPromotionStateEntity)

    @Query("SELECT * FROM tiny_promotion_state WHERE profileKey = :profileKey")
    abstract suspend fun promotionState(profileKey: String): TinyPromotionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRuntimeHealth(value: TinyRuntimeHealthStateEntity)

    @Query("SELECT * FROM tiny_runtime_health_state WHERE profileKey = :profileKey")
    abstract suspend fun runtimeHealth(profileKey: String): TinyRuntimeHealthStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPromotionWindow(value: PromotionEvaluationWindowEntity)

    @Query("SELECT * FROM promotion_evaluation_window WHERE profileKey = :profileKey ORDER BY endEvaluationSeq DESC LIMIT :limit")
    abstract suspend fun promotionWindows(profileKey: String, limit: Int): List<PromotionEvaluationWindowEntity>

    @Query("DELETE FROM promotion_evaluation_window WHERE profileKey = :profileKey AND rowId NOT IN (SELECT rowId FROM promotion_evaluation_window WHERE profileKey = :profileKey ORDER BY endEvaluationSeq DESC LIMIT :limit)")
    abstract suspend fun trimPromotionWindows(profileKey: String, limit: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertActionQualification(value: PromotionActionQualificationEntity)

    @Query("SELECT * FROM promotion_action_qualification WHERE profileKey = :profileKey")
    abstract suspend fun actionQualifications(profileKey: String): List<PromotionActionQualificationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTransitionJournal(value: PromotionTransitionJournalEntity)

    @Query("UPDATE promotion_transition_journal SET state = 'COMMITTED', committedAtEpochMs = :committedAt WHERE journalId = :journalId AND state = 'PREPARED'")
    abstract suspend fun commitTransitionJournal(journalId: String, committedAt: Long): Int

    @Query("DELETE FROM promotion_transition_journal WHERE profileKey = :profileKey AND state = 'COMMITTED' AND journalId NOT IN (SELECT journalId FROM promotion_transition_journal WHERE profileKey = :profileKey ORDER BY preparedAtEpochMs DESC LIMIT :limit)")
    abstract suspend fun trimPromotionTransitionJournals(profileKey: String, limit: Int)

    @Transaction
    open suspend fun persistPromotionTransition(
        journal: PromotionTransitionJournalEntity,
        state: TinyPromotionStateEntity,
        committedAt: Long
    ) {
        insertTransitionJournal(journal)
        upsertPromotionState(state)
        check(commitTransitionJournal(journal.journalId, committedAt) == 1) {
            "promotion transition journal was not committed"
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLearningState(value: LearningStateEntity)

    @Query("SELECT * FROM learning_state WHERE profileKey = :profileKey")
    abstract suspend fun learningState(profileKey: String): LearningStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertLease(value: ProductExecutionLeaseEntity)

    @Query("SELECT * FROM product_execution_lease WHERE executionId = :executionId")
    abstract suspend fun lease(executionId: String): ProductExecutionLeaseEntity?

    @Query("UPDATE product_execution_lease SET state = 'CONSUMED' WHERE executionId = :executionId AND state = 'PREPARED' AND expiresAtElapsedMs > :elapsedMs")
    abstract suspend fun consumeLease(executionId: String, elapsedMs: Long): Int

    @Query("UPDATE product_execution_lease SET state = 'CANCELLED' WHERE profileKey = :profileKey AND state = 'PREPARED'")
    abstract suspend fun cancelProfileLeases(profileKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTelemetryState(value: TelemetryStateEntity)

    @Query("SELECT * FROM telemetry_state WHERE profileKey = :profileKey")
    abstract suspend fun telemetryState(profileKey: String): TelemetryStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTelemetryAggregateWindow(value: TelemetryAggregateWindowEntity)

    @Update
    abstract suspend fun updateTelemetryAggregateWindow(value: TelemetryAggregateWindowEntity)

    @Query("SELECT * FROM telemetry_aggregate_window WHERE profileKey = :profileKey AND consentLifecycleId = :consentLifecycleId AND state = 'OPEN' ORDER BY startEvaluationSeq DESC LIMIT 1")
    abstract suspend fun openTelemetryAggregateWindow(
        profileKey: String,
        consentLifecycleId: String
    ): TelemetryAggregateWindowEntity?

    @Query("SELECT * FROM telemetry_aggregate_window WHERE profileKey = :profileKey AND state = 'OPEN'")
    abstract suspend fun openTelemetryAggregateWindows(profileKey: String): List<TelemetryAggregateWindowEntity>

    @Query("SELECT * FROM telemetry_aggregate_window WHERE profileKey = :profileKey AND consentLifecycleId = :consentLifecycleId AND state = 'CLOSED' ORDER BY startEvaluationSeq LIMIT 1")
    abstract suspend fun nextClosedTelemetryAggregateWindow(
        profileKey: String,
        consentLifecycleId: String
    ): TelemetryAggregateWindowEntity?

    @Query("UPDATE telemetry_aggregate_window SET state = :state, updatedAtEpochMs = :updatedAtEpochMs WHERE windowId = :windowId AND state = :expectedState")
    abstract suspend fun transitionTelemetryAggregateWindow(
        windowId: String,
        expectedState: String,
        state: String,
        updatedAtEpochMs: Long
    ): Int

    @Transaction
    open suspend fun suppressOpenTelemetryAggregateWindowsForCheckpointSwap(
        profileKey: String,
        updatedAtEpochMs: Long
    ) {
        openTelemetryAggregateWindows(profileKey).forEach { window ->
            transitionTelemetryAggregateWindow(
                windowId = window.windowId,
                expectedState = "OPEN",
                state = "SUPPRESSED",
                updatedAtEpochMs = updatedAtEpochMs
            )
        }
    }

    @Query("DELETE FROM telemetry_aggregate_window WHERE profileKey = :profileKey")
    abstract suspend fun deleteTelemetryAggregateWindows(profileKey: String)

    @Query("DELETE FROM telemetry_aggregate_window WHERE consentLifecycleId = :consentLifecycleId")
    abstract suspend fun deleteTelemetryAggregateWindowsForLifecycle(consentLifecycleId: String)

    @Query("DELETE FROM telemetry_aggregate_window WHERE state IN ('CONSUMED', 'SUPPRESSED') AND updatedAtEpochMs < :beforeEpochMs")
    abstract suspend fun deleteOldTerminalTelemetryAggregateWindows(beforeEpochMs: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTelemetryV3AggregateWindow(value: TelemetryV3AggregateWindowEntity)

    @Update
    abstract suspend fun updateTelemetryV3AggregateWindow(value: TelemetryV3AggregateWindowEntity)

    @Query("SELECT * FROM telemetry_v3_aggregate_window WHERE profileKey = :profileKey AND consentLifecycleId = :consentLifecycleId AND task = :task AND state = 'OPEN' ORDER BY createdAtEpochMs DESC LIMIT 1")
    abstract suspend fun openTelemetryV3AggregateWindow(
        profileKey: String,
        consentLifecycleId: String,
        task: String
    ): TelemetryV3AggregateWindowEntity?

    @Query("SELECT * FROM telemetry_v3_aggregate_window WHERE profileKey = :profileKey AND state = 'OPEN'")
    abstract suspend fun openTelemetryV3AggregateWindows(profileKey: String): List<TelemetryV3AggregateWindowEntity>

    @Query("SELECT * FROM telemetry_v3_aggregate_window WHERE profileKey = :profileKey AND consentLifecycleId = :consentLifecycleId AND state = 'CLOSED' ORDER BY createdAtEpochMs LIMIT 1")
    abstract suspend fun nextClosedTelemetryV3AggregateWindow(
        profileKey: String,
        consentLifecycleId: String
    ): TelemetryV3AggregateWindowEntity?

    @Query("UPDATE telemetry_v3_aggregate_window SET state = :state, updatedAtEpochMs = :updatedAtEpochMs WHERE windowId = :windowId AND state = :expectedState")
    abstract suspend fun transitionTelemetryV3AggregateWindow(
        windowId: String,
        expectedState: String,
        state: String,
        updatedAtEpochMs: Long
    ): Int

    @Query("DELETE FROM telemetry_v3_aggregate_window WHERE profileKey = :profileKey")
    abstract suspend fun deleteTelemetryV3AggregateWindows(profileKey: String)

    @Query("DELETE FROM telemetry_v3_aggregate_window WHERE consentLifecycleId = :consentLifecycleId")
    abstract suspend fun deleteTelemetryV3AggregateWindowsForLifecycle(consentLifecycleId: String)

    @Query("DELETE FROM telemetry_v3_aggregate_window WHERE state IN ('CONSUMED', 'SUPPRESSED') AND updatedAtEpochMs < :beforeEpochMs")
    abstract suspend fun deleteOldTerminalTelemetryV3AggregateWindows(beforeEpochMs: Long)

    @Query("SELECT * FROM telemetry_v3_aggregate_window WHERE profileKey = :profileKey ORDER BY createdAtEpochMs DESC LIMIT :limit")
    abstract suspend fun recentTelemetryV3AggregateWindows(
        profileKey: String,
        limit: Int
    ): List<TelemetryV3AggregateWindowEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTelemetryReport(value: TelemetryReportEntity): Long

    @Transaction
    open suspend fun queueTelemetryReport(
        report: TelemetryReportEntity,
        updatedState: TelemetryStateEntity,
        aggregateWindowId: String
    ) {
        check(insertTelemetryReport(report) != -1L) { "telemetry report identity already exists" }
        check(
            transitionTelemetryAggregateWindow(
                aggregateWindowId,
                expectedState = "CLOSED",
                state = "CONSUMED",
                updatedAtEpochMs = report.createdAtEpochMs
            ) == 1
        ) { "telemetry aggregate window was not closed" }
        upsertTelemetryState(updatedState)
    }

    @Transaction
    open suspend fun queueTelemetryV3Report(
        report: TelemetryReportEntity,
        updatedState: TelemetryStateEntity,
        aggregateWindowId: String
    ) {
        check(insertTelemetryReport(report) != -1L) { "telemetry report identity already exists" }
        check(
            transitionTelemetryV3AggregateWindow(
                aggregateWindowId,
                expectedState = "CLOSED",
                state = "CONSUMED",
                updatedAtEpochMs = report.createdAtEpochMs
            ) == 1
        ) { "telemetry v3 aggregate window was not closed" }
        upsertTelemetryState(updatedState)
    }

    @Query("SELECT * FROM telemetry_report WHERE state = 'READY' AND nextAttemptEpochMs <= :nowEpochMs AND expiresAtEpochMs > :nowEpochMs AND (lastAttemptEpochDay IS NULL OR lastAttemptEpochDay < :todayEpochDay) ORDER BY rowId LIMIT :limit")
    abstract suspend fun dueTelemetryReports(nowEpochMs: Long, todayEpochDay: Long, limit: Int): List<TelemetryReportEntity>

    @Query("SELECT MAX(lastAttemptEpochDay) FROM telemetry_report")
    abstract suspend fun lastTelemetryUploadAttemptEpochDay(): Long?

    @Query("UPDATE telemetry_report SET state = :state, attemptCount = :attemptCount, nextAttemptEpochMs = :nextAttemptEpochMs, lastAttemptEpochDay = :lastAttemptEpochDay WHERE reportId = :reportId")
    abstract suspend fun updateTelemetryReport(reportId: String, state: String, attemptCount: Int, nextAttemptEpochMs: Long, lastAttemptEpochDay: Long?)

    @Query("DELETE FROM telemetry_report WHERE profileKey = :profileKey")
    abstract suspend fun deleteTelemetryReports(profileKey: String)

    @Query("DELETE FROM telemetry_report WHERE consentLifecycleId = :consentLifecycleId")
    abstract suspend fun deleteTelemetryReportsForLifecycle(consentLifecycleId: String)

    @Query("DELETE FROM telemetry_report WHERE expiresAtEpochMs <= :nowEpochMs")
    abstract suspend fun deleteExpiredTelemetryReports(nowEpochMs: Long)

    @Query("SELECT COUNT(*) FROM telemetry_report WHERE profileKey = :profileKey AND state = 'READY'")
    abstract suspend fun pendingTelemetryReportCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM telemetry_report WHERE state = 'READY'")
    abstract suspend fun totalReadyTelemetryReportCount(): Int

    @Query("DELETE FROM telemetry_report WHERE rowId IN (SELECT rowId FROM telemetry_report WHERE profileKey = :profileKey ORDER BY rowId DESC LIMIT -1 OFFSET :limit)")
    abstract suspend fun trimTelemetryReports(profileKey: String, limit: Int)

    @Query("DELETE FROM telemetry_state WHERE profileKey = :profileKey")
    abstract suspend fun deleteTelemetryState(profileKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDeletionTombstone(value: TelemetryDeletionTombstoneEntity)

    @Query("SELECT * FROM telemetry_deletion_tombstone WHERE state = 'PENDING' AND nextAttemptEpochMs <= :nowEpochMs AND expiresAtEpochMs > :nowEpochMs ORDER BY createdAtEpochMs LIMIT :limit")
    abstract suspend fun pendingDeletionTombstones(nowEpochMs: Long, limit: Int): List<TelemetryDeletionTombstoneEntity>

    @Query("UPDATE telemetry_deletion_tombstone SET attemptCount = :attemptCount, nextAttemptEpochMs = :nextAttemptEpochMs WHERE deletionId = :deletionId AND state = 'PENDING'")
    abstract suspend fun retryDeletionTombstone(deletionId: String, attemptCount: Int, nextAttemptEpochMs: Long)

    @Query("DELETE FROM telemetry_deletion_tombstone WHERE deletionId = :deletionId")
    abstract suspend fun deleteDeletionTombstone(deletionId: String)

    @Query("SELECT COUNT(*) FROM telemetry_deletion_tombstone WHERE state = 'PENDING'")
    abstract suspend fun pendingDeletionTombstoneCount(): Int

    @Query("SELECT * FROM telemetry_deletion_tombstone WHERE expiresAtEpochMs <= :nowEpochMs")
    abstract suspend fun expiredDeletionTombstones(nowEpochMs: Long): List<TelemetryDeletionTombstoneEntity>

    @Transaction
    open suspend fun revokeTelemetryLifecycle(
        profileKey: String,
        consentLifecycleId: String,
        tombstone: TelemetryDeletionTombstoneEntity
    ) {
        insertDeletionTombstone(tombstone)
        deleteTelemetryReportsForLifecycle(consentLifecycleId)
        deleteTelemetryAggregateWindowsForLifecycle(consentLifecycleId)
        deleteTelemetryV3AggregateWindowsForLifecycle(consentLifecycleId)
        deleteTelemetryState(profileKey)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSemanticEvent(value: SemanticEventEntity)

    @Query("SELECT * FROM semantic_event WHERE profileKey = :profileKey ORDER BY sequenceNo DESC LIMIT :limit")
    abstract suspend fun recentSemanticEvents(profileKey: String, limit: Int): List<SemanticEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSemanticChangeSet(value: SemanticChangeSetEntity)

    @Query("SELECT * FROM semantic_change_set WHERE profileKey = :profileKey AND sessionId = :sessionId AND state IN ('PREPARED', 'OPEN', 'COMMITTED') AND route IS :route AND lastOccurredAtElapsedMs >= :minimumElapsedMs ORDER BY lastOccurredAtElapsedMs DESC LIMIT 1")
    abstract suspend fun mergeableSemanticChangeSet(
        profileKey: String,
        sessionId: String,
        route: String?,
        minimumElapsedMs: Long
    ): SemanticChangeSetEntity?

    @Query("SELECT * FROM semantic_change_set WHERE profileKey = :profileKey ORDER BY lastOccurredAtElapsedMs DESC LIMIT :limit")
    abstract suspend fun recentSemanticChangeSets(profileKey: String, limit: Int): List<SemanticChangeSetEntity>

    @Query("UPDATE semantic_change_set SET state = :state WHERE changeSetId = :changeSetId")
    abstract suspend fun updateSemanticChangeSetState(changeSetId: String, state: String): Int

    @Query("UPDATE semantic_change_set SET state = 'RECOVERED' WHERE profileKey = :profileKey AND state = 'PREPARED'")
    abstract suspend fun recoverPreparedSemanticChangeSets(profileKey: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPendingJourney(value: PendingJourneyEntity)

    @Update
    abstract suspend fun updatePendingJourney(value: PendingJourneyEntity)

    @Query("SELECT * FROM pending_journey WHERE journeyId = :journeyId")
    abstract suspend fun pendingJourney(journeyId: String): PendingJourneyEntity?

    @Query("SELECT * FROM pending_journey WHERE profileKey = :profileKey AND resolutionStatus = 'PENDING' ORDER BY sequenceNo DESC LIMIT 1")
    abstract suspend fun latestPendingJourney(profileKey: String): PendingJourneyEntity?

    @Query("SELECT * FROM pending_journey WHERE profileKey = :profileKey AND resolutionStatus = 'PENDING' AND processInstanceId = :processInstanceId AND deadlineElapsedMs <= :elapsedMs")
    abstract suspend fun expiredPendingJourneys(profileKey: String, processInstanceId: String, elapsedMs: Long): List<PendingJourneyEntity>

    @Query("UPDATE pending_journey SET resolutionStatus = 'CENSORED', censorReason = :reason WHERE journeyId = :journeyId AND profileKey = :profileKey AND resolutionStatus = 'PENDING'")
    abstract suspend fun censorJourneyCas(journeyId: String, profileKey: String, reason: String): Int

    @Query("UPDATE pending_journey SET resolutionStatus = 'RESOLVED', finalTargetActionId = :targetActionId, consumedTerminalEventId = :terminalEventId WHERE journeyId = :journeyId AND profileKey = :profileKey AND resolutionStatus = 'PENDING' AND interventionState = 'NONE'")
    abstract suspend fun resolveJourneyCas(
        journeyId: String,
        profileKey: String,
        targetActionId: String,
        terminalEventId: String
    ): Int

    @Query("UPDATE pending_journey SET resolutionStatus = 'CENSORED', censorReason = 'PROCESS_RESTART' WHERE profileKey = :profileKey AND resolutionStatus = 'PENDING' AND processInstanceId != :processInstanceId")
    abstract suspend fun censorStaleProcessJourneys(profileKey: String, processInstanceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertJourneyActionStat(value: JourneyActionStatEntity)

    @Query("SELECT * FROM journey_action_stat WHERE profileKey = :profileKey")
    abstract suspend fun journeyActionStats(profileKey: String): List<JourneyActionStatEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertJourneyTrainingSample(value: JourneyTrainingSampleEntity): Long

    @Query("SELECT COUNT(*) FROM journey_training_sample WHERE profileKey = :profileKey")
    abstract suspend fun journeyTrainingSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM journey_training_sample WHERE profileKey = :profileKey AND targetActionId != 'NONE'")
    abstract suspend fun journeyNonNoneSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(DISTINCT targetFamily) FROM journey_training_sample WHERE profileKey = :profileKey AND targetActionId != 'NONE'")
    abstract suspend fun journeyTargetFamilyCount(profileKey: String): Int

    @Query("SELECT * FROM journey_training_sample WHERE profileKey = :profileKey ORDER BY rowId DESC LIMIT :limit")
    abstract suspend fun recentJourneyTrainingSamples(profileKey: String, limit: Int): List<JourneyTrainingSampleEntity>

    @Query("UPDATE journey_training_sample SET trainingCount = trainingCount + 1 WHERE rowId IN (:rowIds)")
    abstract suspend fun incrementJourneyTrainingCounts(rowIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertJourneyEvaluation(value: JourneyShadowEvaluationEntity)

    @Query("SELECT COALESCE(MAX(evaluationSeq), 0) FROM journey_shadow_evaluation WHERE profileKey = :profileKey")
    abstract suspend fun maxJourneyEvaluationSeq(profileKey: String): Long

    @Query("SELECT * FROM journey_shadow_evaluation WHERE profileKey = :profileKey ORDER BY evaluationSeq DESC LIMIT :limit")
    abstract suspend fun recentJourneyEvaluations(profileKey: String, limit: Int): List<JourneyShadowEvaluationEntity>

    @Query("SELECT * FROM journey_shadow_evaluation WHERE profileKey = :profileKey AND evaluationSeq > :afterSeq AND tinyCheckpointId = :checkpointId ORDER BY evaluationSeq ASC LIMIT :limit")
    abstract suspend fun journeyEvaluationsAfter(
        profileKey: String,
        checkpointId: String,
        afterSeq: Long,
        limit: Int
    ): List<JourneyShadowEvaluationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLocalPreset(value: LocalParameterPresetEntity)

    @Query("SELECT * FROM local_parameter_preset WHERE profileKey = :profileKey AND domainId = :domainId ORDER BY lastOrganicUsedAtEpochMs DESC, createdAtEpochMs DESC LIMIT :limit")
    abstract suspend fun recentLocalPresets(profileKey: String, domainId: String, limit: Int): List<LocalParameterPresetEntity>

    @Query("SELECT * FROM local_parameter_preset WHERE presetId = :presetId AND profileKey = :profileKey")
    abstract suspend fun localPreset(profileKey: String, presetId: String): LocalParameterPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPresetUsageStat(value: PresetUsageStatEntity)

    @Query("SELECT * FROM preset_usage_stat WHERE profileKey = :profileKey AND domainId = :domainId")
    abstract suspend fun presetUsageStats(profileKey: String, domainId: String): List<PresetUsageStatEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTargetedFeedback(value: TargetedPredictionFeedbackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPresetInteraction(value: PresetRecommendationInteractionEntity): Long

    @Query("SELECT * FROM preset_recommendation_interaction WHERE interactionId = :interactionId AND profileKey = :profileKey")
    abstract suspend fun presetInteraction(profileKey: String, interactionId: String): PresetRecommendationInteractionEntity?

    @Query("SELECT * FROM preset_recommendation_interaction WHERE profileKey = :profileKey AND domainId = :domainId AND state IN ('EXPOSED', 'APPLIED') ORDER BY shownAtEpochMs DESC LIMIT 1")
    abstract suspend fun activePresetInteraction(profileKey: String, domainId: String): PresetRecommendationInteractionEntity?

    @Query("SELECT * FROM preset_recommendation_interaction WHERE profileKey = :profileKey ORDER BY shownAtEpochMs DESC LIMIT :limit")
    abstract suspend fun recentPresetInteractions(profileKey: String, limit: Int): List<PresetRecommendationInteractionEntity>

    @Query("SELECT * FROM preset_recommendation_interaction WHERE profileKey = :profileKey AND opportunityId = :opportunityId AND candidateId = :candidateId LIMIT 1")
    abstract suspend fun presetInteractionForCandidate(profileKey: String, opportunityId: String, candidateId: String): PresetRecommendationInteractionEntity?

    @Query("UPDATE preset_recommendation_interaction SET state = 'APPLIED', appliedAtEpochMs = :appliedAtEpochMs WHERE interactionId = :interactionId AND profileKey = :profileKey AND state = 'EXPOSED'")
    abstract suspend fun markPresetInteractionAppliedCas(profileKey: String, interactionId: String, appliedAtEpochMs: Long): Int

    @Query("UPDATE preset_recommendation_interaction SET state = :resolvedState, resolvedAtEpochMs = :resolvedAtEpochMs, resolutionFingerprint = :resolutionFingerprint, feedbackWeight = :feedbackWeight WHERE interactionId = :interactionId AND profileKey = :profileKey AND state = 'APPLIED'")
    abstract suspend fun resolvePresetInteractionCas(
        profileKey: String,
        interactionId: String,
        resolvedState: String,
        resolvedAtEpochMs: Long,
        resolutionFingerprint: String?,
        feedbackWeight: Float?
    ): Int

    @Query("UPDATE preset_recommendation_interaction SET state = 'EXPIRED_NO_LABEL', resolvedAtEpochMs = :resolvedAtEpochMs WHERE interactionId = :interactionId AND profileKey = :profileKey AND state IN ('EXPOSED', 'APPLIED')")
    abstract suspend fun expirePresetInteractionCas(profileKey: String, interactionId: String, resolvedAtEpochMs: Long): Int

    @Query("UPDATE preset_recommendation_interaction SET state = 'EXPIRED_NO_LABEL', resolvedAtEpochMs = :resolvedAtEpochMs WHERE profileKey = :profileKey AND domainId = :domainId AND state IN ('EXPOSED', 'APPLIED')")
    abstract suspend fun expireActivePresetInteractions(profileKey: String, domainId: String, resolvedAtEpochMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTaskModelState(value: TaskModelStateEntity)

    @Query("SELECT * FROM task_model_state WHERE profileKey = :profileKey AND modelTask = :modelTask")
    abstract suspend fun taskModelState(profileKey: String, modelTask: String): TaskModelStateEntity?

    @Query("SELECT * FROM task_model_state WHERE profileKey = :profileKey ORDER BY modelTask")
    abstract suspend fun taskModelStates(profileKey: String): List<TaskModelStateEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTaskTrainingJournal(value: TaskTrainingBatchJournalEntity): Long

    @Query("SELECT * FROM task_training_batch_journal WHERE profileKey = :profileKey AND modelTask = :modelTask AND state = 'PREPARED' ORDER BY createdAtEpochMs LIMIT 1")
    abstract suspend fun preparedTaskTrainingJournal(profileKey: String, modelTask: String): TaskTrainingBatchJournalEntity?

    @Query("UPDATE task_training_batch_journal SET state = 'COMMITTED', committedAtEpochMs = :committedAtEpochMs WHERE batchId = :batchId AND state = 'PREPARED'")
    abstract suspend fun commitTaskTrainingJournal(batchId: String, committedAtEpochMs: Long): Int

    @Query("UPDATE task_training_batch_journal SET state = 'ABANDONED', committedAtEpochMs = :committedAtEpochMs WHERE batchId = :batchId AND state = 'PREPARED'")
    abstract suspend fun abandonTaskTrainingJournal(batchId: String, committedAtEpochMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPresetTrainingSample(value: PresetTrainingSampleEntity): Long

    @Query("SELECT * FROM preset_training_sample WHERE profileKey = :profileKey ORDER BY rowId DESC LIMIT :limit")
    abstract suspend fun recentPresetTrainingSamples(profileKey: String, limit: Int): List<PresetTrainingSampleEntity>

    @Query("SELECT COUNT(*) FROM preset_training_sample WHERE profileKey = :profileKey")
    abstract suspend fun presetTrainingSampleCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM preset_training_sample WHERE profileKey = :profileKey AND naturalHoldoutEligible = 1 AND feedbackSource = 'NATURAL_COMMIT'")
    abstract suspend fun naturalPresetTrainingSampleCount(profileKey: String): Int

    @Query("UPDATE preset_training_sample SET trainingCount = trainingCount + 1 WHERE rowId IN (:rowIds)")
    abstract suspend fun incrementPresetTrainingCounts(rowIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPresetShadowEvaluation(value: PresetShadowEvaluationEntity)

    @Query("SELECT * FROM preset_shadow_evaluation WHERE profileKey = :profileKey ORDER BY rowId DESC LIMIT :limit")
    abstract suspend fun recentPresetShadowEvaluations(profileKey: String, limit: Int): List<PresetShadowEvaluationEntity>

    @Query("SELECT * FROM preset_shadow_evaluation WHERE profileKey = :profileKey AND rowId > :afterSeq AND tinyCheckpointId = :checkpointId AND naturalHoldoutEligible = 1 AND evaluationSource = 'ORGANIC' ORDER BY rowId ASC LIMIT :limit")
    abstract suspend fun presetShadowEvaluationsAfter(
        profileKey: String,
        checkpointId: String,
        afterSeq: Long,
        limit: Int
    ): List<PresetShadowEvaluationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBootstrapTrainingConsent(value: BootstrapTrainingConsentEntity)

    @Query("SELECT * FROM bootstrap_training_consent WHERE profileKey = :profileKey")
    abstract suspend fun bootstrapTrainingConsent(profileKey: String): BootstrapTrainingConsentEntity?

    @Query("SELECT * FROM bootstrap_training_consent WHERE state = 'ACTIVE' ORDER BY createdAtEpochMs")
    abstract suspend fun activeBootstrapTrainingConsents(): List<BootstrapTrainingConsentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBootstrapTrainingExample(value: BootstrapTrainingExampleEntity): Long

    @Query("SELECT COUNT(*) FROM bootstrap_training_example WHERE profileKey = :profileKey AND exampleId IN (:exampleIds)")
    abstract suspend fun bootstrapTrainingExampleCountByIds(profileKey: String, exampleIds: List<String>): Int

    @Query("SELECT * FROM bootstrap_training_example WHERE profileKey = :profileKey AND state = 'PENDING' ORDER BY sequenceNo ASC LIMIT :limit")
    abstract suspend fun pendingBootstrapTrainingExamples(
        profileKey: String,
        limit: Int
    ): List<BootstrapTrainingExampleEntity>

    @Query("SELECT COUNT(*) FROM bootstrap_training_example WHERE profileKey = :profileKey AND state IN ('PENDING', 'BATCHED')")
    abstract suspend fun pendingBootstrapTrainingExampleCount(profileKey: String): Int

    @Query("SELECT COUNT(*) FROM bootstrap_training_example WHERE profileKey = :profileKey AND task = :task AND state = 'PENDING'")
    abstract suspend fun pendingBootstrapTrainingTaskCount(profileKey: String, task: String): Int

    @Query("DELETE FROM bootstrap_training_example WHERE rowId IN (SELECT rowId FROM bootstrap_training_example WHERE profileKey = :profileKey AND task = :task AND state = 'PENDING' ORDER BY sequenceNo ASC LIMIT :count)")
    abstract suspend fun evictOldestPendingBootstrapTrainingExamples(
        profileKey: String,
        task: String,
        count: Int
    ): Int

    @Query("DELETE FROM bootstrap_training_example WHERE rowId IN (:rowIds) AND state = 'PENDING'")
    abstract suspend fun deletePendingBootstrapTrainingExamples(rowIds: List<Long>): Int

    @Query("UPDATE bootstrap_training_example SET state = 'BATCHED', batchId = :batchId WHERE rowId IN (:rowIds) AND state = 'PENDING'")
    abstract suspend fun markBootstrapTrainingExamplesBatched(rowIds: List<Long>, batchId: String): Int

    @Query("UPDATE bootstrap_training_example SET state = 'QUARANTINED', batchId = NULL WHERE rowId IN (:rowIds) AND state = 'PENDING'")
    abstract suspend fun quarantineBootstrapTrainingExamples(rowIds: List<Long>): Int

    @Query("DELETE FROM bootstrap_training_example WHERE state = 'QUARANTINED' AND createdAtEpochMs < :cutoffEpochMs")
    abstract suspend fun deleteExpiredQuarantinedBootstrapTrainingExamples(cutoffEpochMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBootstrapTrainingBatch(value: BootstrapTrainingBatchEntity)

    @Query("SELECT * FROM bootstrap_training_batch WHERE state IN ('READY', 'RETRY') AND nextAttemptAtEpochMs <= :nowEpochMs ORDER BY createdAtEpochMs ASC LIMIT 1")
    abstract suspend fun dueBootstrapTrainingBatch(nowEpochMs: Long): BootstrapTrainingBatchEntity?

    @Query("SELECT COUNT(*) FROM bootstrap_training_batch WHERE state IN ('READY', 'RETRY')")
    abstract suspend fun pendingBootstrapTrainingBatchCount(): Int

    @Query("SELECT COUNT(*) FROM bootstrap_training_batch WHERE profileKey = :profileKey AND state IN ('READY', 'RETRY')")
    abstract suspend fun activeBootstrapTrainingBatchCount(profileKey: String): Int

    @Query("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM bootstrap_training_batch WHERE profileKey = :profileKey AND state IN ('READY', 'RETRY')")
    abstract suspend fun activeBootstrapTrainingBatchBytes(profileKey: String): Long

    @Query("SELECT MIN(nextAttemptAtEpochMs) FROM bootstrap_training_batch WHERE state IN ('READY', 'RETRY')")
    abstract suspend fun nextBootstrapTrainingBatchAttemptAtEpochMs(): Long?

    @Query("SELECT * FROM bootstrap_training_batch WHERE batchId = :batchId")
    abstract suspend fun bootstrapTrainingBatch(batchId: String): BootstrapTrainingBatchEntity?

    @Query("SELECT batchId FROM bootstrap_training_batch WHERE state = 'QUARANTINED' OR createdAtEpochMs < :cutoffEpochMs")
    abstract suspend fun staleBootstrapTrainingBatchIds(cutoffEpochMs: Long): List<String>

    @Query("DELETE FROM bootstrap_training_example WHERE batchId IN (:batchIds)")
    abstract suspend fun deleteBootstrapTrainingExamplesForBatches(batchIds: List<String>): Int

    @Query("DELETE FROM bootstrap_training_batch WHERE batchId IN (:batchIds)")
    abstract suspend fun deleteBootstrapTrainingBatches(batchIds: List<String>): Int

    @Query("UPDATE bootstrap_training_batch SET state = 'RETRY', attemptCount = attemptCount + 1, nextAttemptAtEpochMs = :nextAttemptAtEpochMs, lastErrorCode = :errorCode WHERE batchId = :batchId AND state IN ('READY', 'RETRY')")
    abstract suspend fun retryBootstrapTrainingBatch(
        batchId: String,
        nextAttemptAtEpochMs: Long,
        errorCode: String
    ): Int

    @Query("UPDATE bootstrap_training_batch SET state = 'ACKNOWLEDGED', acknowledgedAtEpochMs = :acknowledgedAtEpochMs, lastErrorCode = NULL WHERE batchId = :batchId AND state IN ('READY', 'RETRY')")
    abstract suspend fun acknowledgeBootstrapTrainingBatchState(
        batchId: String,
        acknowledgedAtEpochMs: Long
    ): Int

    @Query("DELETE FROM bootstrap_training_example WHERE batchId = :batchId AND state = 'BATCHED'")
    abstract suspend fun deleteAcknowledgedBootstrapTrainingExamples(batchId: String): Int

    @Query("DELETE FROM bootstrap_training_batch WHERE batchId = :batchId AND state = 'ACKNOWLEDGED'")
    abstract suspend fun deleteAcknowledgedBootstrapTrainingBatch(batchId: String): Int

    @Transaction
    open suspend fun acknowledgeBootstrapTrainingBatch(batchId: String, acknowledgedAtEpochMs: Long): Int {
        val batch = bootstrapTrainingBatch(batchId) ?: return 0
        if (acknowledgeBootstrapTrainingBatchState(batchId, acknowledgedAtEpochMs) != 1) return 0
        val deleted = deleteAcknowledgedBootstrapTrainingExamples(batchId)
        val consent = bootstrapTrainingConsent(batch.profileKey)
        if (consent != null && consent.consentLifecycleId == batch.consentLifecycleId) {
            upsertBootstrapTrainingConsent(
                consent.copy(
                    contributedExampleCount = consent.contributedExampleCount + deleted,
                    lastUploadAtEpochMs = acknowledgedAtEpochMs,
                    updatedAtEpochMs = acknowledgedAtEpochMs
                )
            )
        }
        deleteAcknowledgedBootstrapTrainingBatch(batchId)
        return deleted
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBootstrapTrainingDeletionTombstone(
        value: BootstrapTrainingDeletionTombstoneEntity
    ): Long

    @Query("SELECT * FROM bootstrap_training_deletion_tombstone WHERE state IN ('READY', 'RETRY') ORDER BY createdAtEpochMs")
    abstract suspend fun bootstrapTrainingDeletionTombstones(): List<BootstrapTrainingDeletionTombstoneEntity>

    @Query("DELETE FROM bootstrap_training_deletion_tombstone WHERE deletionId = :deletionId")
    abstract suspend fun deleteBootstrapTrainingDeletionTombstone(deletionId: String): Int

    @Query("SELECT * FROM bootstrap_training_deletion_tombstone WHERE state IN ('READY', 'RETRY') AND nextAttemptAtEpochMs <= :nowEpochMs ORDER BY createdAtEpochMs ASC LIMIT 1")
    abstract suspend fun dueBootstrapTrainingDeletion(nowEpochMs: Long): BootstrapTrainingDeletionTombstoneEntity?

    @Query("SELECT COUNT(*) FROM bootstrap_training_deletion_tombstone WHERE state IN ('READY', 'RETRY')")
    abstract suspend fun pendingBootstrapTrainingDeletionCount(): Int

    @Query("UPDATE bootstrap_training_batch SET state = 'QUARANTINED', lastErrorCode = :errorCode, nextAttemptAtEpochMs = 9223372036854775807 WHERE batchId = :batchId AND state IN ('READY', 'RETRY')")
    abstract suspend fun quarantineBootstrapTrainingBatch(batchId: String, errorCode: String): Int

    @Query("UPDATE bootstrap_training_deletion_tombstone SET state = 'RETRY', attemptCount = attemptCount + 1, nextAttemptAtEpochMs = :nextAttemptAtEpochMs, lastErrorCode = :errorCode WHERE deletionId = :deletionId AND state IN ('READY', 'RETRY')")
    abstract suspend fun retryBootstrapTrainingDeletion(
        deletionId: String,
        nextAttemptAtEpochMs: Long,
        errorCode: String
    ): Int

    @Query("UPDATE bootstrap_training_deletion_tombstone SET state = 'ACKNOWLEDGED', acknowledgedAtEpochMs = :acknowledgedAtEpochMs, lastErrorCode = NULL WHERE deletionId = :deletionId AND state IN ('READY', 'RETRY')")
    abstract suspend fun acknowledgeBootstrapTrainingDeletion(
        deletionId: String,
        acknowledgedAtEpochMs: Long
    ): Int

    @Query("DELETE FROM bootstrap_training_consent WHERE profileKey = :profileKey")
    abstract suspend fun deleteBootstrapTrainingConsent(profileKey: String)

    @Query("DELETE FROM bootstrap_training_example WHERE profileKey = :profileKey")
    abstract suspend fun deleteBootstrapTrainingExamples(profileKey: String)

    @Query("DELETE FROM bootstrap_training_batch WHERE profileKey = :profileKey")
    abstract suspend fun deleteBootstrapTrainingBatches(profileKey: String)

    @Transaction
    open suspend fun deleteBootstrapTrainingProfileState(profileKey: String) {
        deleteBootstrapTrainingExamples(profileKey)
        deleteBootstrapTrainingBatches(profileKey)
        deleteBootstrapTrainingConsent(profileKey)
    }

    @Query("DELETE FROM behavior_event WHERE profileKey = :profileKey") abstract suspend fun deleteEvents(profileKey: String)
    @Query("DELETE FROM pending_prediction WHERE profileKey = :profileKey") abstract suspend fun deletePending(profileKey: String)
    @Query("DELETE FROM product_execution_lease WHERE profileKey = :profileKey") abstract suspend fun deleteLeases(profileKey: String)
    @Query("DELETE FROM action_stat WHERE profileKey = :profileKey") abstract suspend fun deleteStats(profileKey: String)
    @Query("DELETE FROM training_sample WHERE profileKey = :profileKey") abstract suspend fun deleteSamples(profileKey: String)
    @Query("DELETE FROM training_batch_journal WHERE profileKey = :profileKey") abstract suspend fun deleteTrainingBatchJournals(profileKey: String)
    @Query("DELETE FROM shadow_evaluation WHERE profileKey = :profileKey") abstract suspend fun deleteEvaluations(profileKey: String)
    @Query("DELETE FROM candidate_shadow_evaluation WHERE profileKey = :profileKey") abstract suspend fun deleteCandidateEvaluations(profileKey: String)
    @Query("DELETE FROM tiny_promotion_state WHERE profileKey = :profileKey") abstract suspend fun deletePromotionState(profileKey: String)
    @Query("DELETE FROM tiny_runtime_health_state WHERE profileKey = :profileKey") abstract suspend fun deleteRuntimeHealth(profileKey: String)
    @Query("DELETE FROM promotion_evaluation_window WHERE profileKey = :profileKey") abstract suspend fun deletePromotionWindows(profileKey: String)
    @Query("DELETE FROM promotion_action_qualification WHERE profileKey = :profileKey") abstract suspend fun deleteActionQualifications(profileKey: String)
    @Query("DELETE FROM promotion_transition_journal WHERE profileKey = :profileKey") abstract suspend fun deleteTransitionJournals(profileKey: String)
    @Query("DELETE FROM learning_state WHERE profileKey = :profileKey") abstract suspend fun deleteLearningState(profileKey: String)
    @Query("DELETE FROM semantic_event WHERE profileKey = :profileKey") abstract suspend fun deleteSemanticEvents(profileKey: String)
    @Query("DELETE FROM semantic_change_set WHERE profileKey = :profileKey") abstract suspend fun deleteSemanticChangeSets(profileKey: String)
    @Query("DELETE FROM pending_journey WHERE profileKey = :profileKey") abstract suspend fun deletePendingJourneys(profileKey: String)
    @Query("DELETE FROM journey_action_stat WHERE profileKey = :profileKey") abstract suspend fun deleteJourneyStats(profileKey: String)
    @Query("DELETE FROM journey_training_sample WHERE profileKey = :profileKey") abstract suspend fun deleteJourneySamples(profileKey: String)
    @Query("DELETE FROM journey_shadow_evaluation WHERE profileKey = :profileKey") abstract suspend fun deleteJourneyEvaluations(profileKey: String)
    @Query("DELETE FROM local_parameter_preset WHERE profileKey = :profileKey") abstract suspend fun deleteLocalPresets(profileKey: String)
    @Query("DELETE FROM preset_usage_stat WHERE profileKey = :profileKey") abstract suspend fun deletePresetStats(profileKey: String)
    @Query("DELETE FROM targeted_prediction_feedback WHERE profileKey = :profileKey") abstract suspend fun deleteTargetedFeedback(profileKey: String)
    @Query("DELETE FROM preset_recommendation_interaction WHERE profileKey = :profileKey") abstract suspend fun deletePresetInteractions(profileKey: String)
    @Query("DELETE FROM task_model_state WHERE profileKey = :profileKey") abstract suspend fun deleteTaskModelStates(profileKey: String)
    @Query("DELETE FROM task_training_batch_journal WHERE profileKey = :profileKey") abstract suspend fun deleteTaskTrainingJournals(profileKey: String)
    @Query("DELETE FROM preset_training_sample WHERE profileKey = :profileKey") abstract suspend fun deletePresetTrainingSamples(profileKey: String)
    @Query("DELETE FROM preset_shadow_evaluation WHERE profileKey = :profileKey") abstract suspend fun deletePresetShadowEvaluations(profileKey: String)

    @Transaction
    open suspend fun deletePredictionModelStateForSchema(profileKey: String) {
        deletePending(profileKey)
        deleteLeases(profileKey)
        deleteStats(profileKey)
        deleteSamples(profileKey)
        deleteTrainingBatchJournals(profileKey)
        deleteCandidateEvaluations(profileKey)
        deletePromotionState(profileKey)
        deleteRuntimeHealth(profileKey)
        deletePromotionWindows(profileKey)
        deleteActionQualifications(profileKey)
        deleteTransitionJournals(profileKey)
        deleteLearningState(profileKey)
        deletePendingJourneys(profileKey)
        deleteJourneyStats(profileKey)
        deleteJourneySamples(profileKey)
        deleteLocalPresets(profileKey)
        deletePresetStats(profileKey)
        deleteTaskModelStates(profileKey)
        deleteTaskTrainingJournals(profileKey)
        deletePresetTrainingSamples(profileKey)
        deletePresetInteractions(profileKey)
    }

    @Transaction
    open suspend fun deleteProfileLearningState(profileKey: String, keepDeletionTombstones: Boolean = true) {
        deleteEvents(profileKey)
        deleteSemanticEvents(profileKey)
        deleteSemanticChangeSets(profileKey)
        deletePredictionModelStateForSchema(profileKey)
        deleteEvaluations(profileKey)
        deleteJourneyEvaluations(profileKey)
        deletePresetShadowEvaluations(profileKey)
        deleteTargetedFeedback(profileKey)
        deleteTelemetryReports(profileKey)
        deleteTelemetryAggregateWindows(profileKey)
        deleteTelemetryV3AggregateWindows(profileKey)
        deleteTelemetryState(profileKey)
        deleteBootstrapTrainingProfileState(profileKey)
        @Suppress("UNUSED_VARIABLE") val tombstonesRemainDurable = keepDeletionTombstones
    }

    @Query("DELETE FROM behavior_event WHERE profileKey = :profileKey AND occurredAtEpochMs < :minimumEpochMs")
    abstract suspend fun deleteExpiredEvents(profileKey: String, minimumEpochMs: Long)

    @Query("DELETE FROM behavior_event WHERE profileKey = :profileKey AND eventId NOT IN (SELECT eventId FROM behavior_event WHERE profileKey = :profileKey ORDER BY sequenceNo DESC LIMIT :limit)")
    abstract suspend fun trimEvents(profileKey: String, limit: Int)
}
