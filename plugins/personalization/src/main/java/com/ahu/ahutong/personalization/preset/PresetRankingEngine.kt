package com.ahu.ahutong.personalization.preset

import com.ahu.ahutong.personalization.context.ContextSnapshot
import com.ahu.ahutong.personalization.bootstrap.BootstrapTrainingDataManager
import com.ahu.ahutong.personalization.bootstrap.BootstrapPresetCapture
import com.ahu.ahutong.personalization.inference.AdamWState
import com.ahu.ahutong.personalization.inference.TinyMlpBackprop
import com.ahu.ahutong.personalization.inference.TinyMlpMath
import com.ahu.ahutong.personalization.model.ModelTask
import com.ahu.ahutong.personalization.promotion.PromotionStage
import com.ahu.ahutong.personalization.semantic.SemanticDomain
import com.ahu.ahutong.personalization.storage.BehaviorDao
import com.ahu.ahutong.personalization.storage.BinaryCodec
import com.ahu.ahutong.personalization.storage.LocalParameterPresetEntity
import com.ahu.ahutong.personalization.storage.PresetShadowEvaluationEntity
import com.ahu.ahutong.personalization.storage.PresetRecommendationInteractionEntity
import com.ahu.ahutong.personalization.storage.PresetTrainingSampleEntity
import com.ahu.ahutong.personalization.storage.PresetUsageStatEntity
import com.ahu.ahutong.personalization.storage.TargetedPredictionFeedbackEntity
import com.ahu.ahutong.personalization.storage.TaskModelStateEntity
import com.ahu.ahutong.personalization.storage.TaskTrainingBatchJournalEntity
import com.ahu.ahutong.personalization.telemetry.TelemetryAggregateStore
import com.ahu.ahutong.personalization.telemetry.TelemetryPresetEvent
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object PresetReplayPolicy {
    fun select(
        samples: List<PresetTrainingSampleEntity>,
        minimumNaturalSamples: Int,
        batchSize: Int,
        maximumWeakRows: Int
    ): List<PresetTrainingSampleEntity> {
        val replayOrder = compareBy<PresetTrainingSampleEntity> { it.trainingCount }.thenByDescending { it.rowId }
        val natural = samples.filter {
            it.naturalHoldoutEligible && it.feedbackSource == PresetFeedbackSource.NATURAL_COMMIT.name
        }.sortedWith(replayOrder)
        if (natural.size < minimumNaturalSamples || natural.none(PresetTrainingSampleEntity::label)) return emptyList()
        val selectedNatural = natural.take(batchSize - maximumWeakRows)
        val weakRowLimit = minOf(
            batchSize - selectedNatural.size,
            maximumWeakRows,
            selectedNatural.size / 3
        ).coerceAtLeast(0)
        val maximumWeakMass = selectedNatural.sumOf { it.sampleWeight.toDouble() } * 0.25
        var weakMass = 0.0
        val selectedWeak = mutableListOf<PresetTrainingSampleEntity>()
        samples.asSequence()
            .filterNot(PresetTrainingSampleEntity::naturalHoldoutEligible)
            .sortedWith(replayOrder)
            .forEach { sample ->
                if (selectedWeak.size >= weakRowLimit) return@forEach
                if (weakMass + sample.sampleWeight <= maximumWeakMass + 1e-6) {
                    selectedWeak += sample
                    weakMass += sample.sampleWeight
                }
            }
        return selectedNatural + selectedWeak
    }
}

class PresetRankingEngine constructor(
    private val dao: BehaviorDao,
    private val store: PresetModelStateStore,
    private val telemetryAggregateStore: TelemetryAggregateStore,
    private val bootstrapTrainingDataManager: BootstrapTrainingDataManager? = null
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun shutdown() { trainerDispatcher.close() }

    private val pendingProfiles = ConcurrentHashMap.newKeySet<String>()
    private val lastRankings = ConcurrentHashMap<Pair<String, SemanticDomain>, List<PresetCandidate>>()
    private val trainerDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "preset-tiny-trainer").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    suspend fun rank(
        profileKey: String,
        domain: SemanticDomain,
        snapshot: ContextSnapshot,
        holdout: Boolean
    ): List<PresetCandidate> = locks.getOrPut(profileKey) { Mutex() }.withLock {
        require(domain in SUPPORTED_DOMAINS)
        val presets = dao.recentLocalPresets(profileKey, domain.name, 32)
        if (presets.isEmpty()) return@withLock emptyList()
        val stats = dao.presetUsageStats(profileKey, domain.name)
        val contexts = contextKeys(snapshot)
        val byContext = stats.groupBy { it.contextKey }
        val recent = presets.firstOrNull()
        val timeTop = topPreset(byContext[contexts.time].orEmpty(), presets)
        val globalTop = topPreset(byContext[contexts.global].orEmpty(), presets)
        val businessTop = topPreset(byContext[contexts.business].orEmpty(), presets)
        val selected = listOfNotNull(recent, timeTop, globalTop, businessTop).distinctBy(LocalParameterPresetEntity::presetId).take(MAX_CANDIDATES)
        val model = store.state(profileKey)
        val taskState = ensureTaskState(profileKey, model)
        val scoringCheckpoint = model.candidate ?: model.active
        val opportunityId = UUID.randomUUID().toString()
        selected.mapIndexed { index, preset ->
            val features = candidateFeatures(index, preset, stats, contexts, snapshot)
            val statScore = statisticalScore(preset.presetId, stats, contexts)
            val tinyScore = TinyMlpMath.forward(scoringCheckpoint.parameters, features).probabilities[1]
            val lambda = if (taskState.healthState == "HEALTHY") taskState.mixedLambda else 0f
            PresetCandidate(
                opportunityId,
                preset.presetId,
                domain,
                preset.localPayloadJson,
                preset.coarseFeaturesJson,
                statScore,
                tinyScore,
                (1f - lambda) * statScore + lambda * tinyScore,
                when (preset.presetId) {
                    recent?.presetId -> "RECENT"
                    timeTop?.presetId -> "TIME_BUCKET"
                    businessTop?.presetId -> "BUSINESS_CONTEXT"
                    else -> "GLOBAL_FREQUENCY"
                },
                candidateFingerprint = preset.fingerprint,
                checkpointId = scoringCheckpoint.id,
                featureVector = features,
                recentBaselineScore = if (index == 0) 1f else 0f,
                frequencyBaselineScore = statisticalScore(
                    preset.presetId,
                    stats,
                    contexts.copy(time = contexts.global, business = contexts.global)
                ),
                promotionHoldout = holdout,
                candidateOrdinal = index,
                occurredEpochDay = snapshot.epochDay
            )
        }.sortedByDescending(PresetCandidate::effectiveScore).let { ranked ->
            lastRankings[profileKey to domain] = ranked
            if (holdout) emptyList() else ranked
        }
    }

    suspend fun recordNaturalSubmission(
        profileKey: String,
        submission: PresetSubmission,
        snapshot: ContextSnapshot,
        interactionToken: PresetInteractionToken?,
        candidatesAtOpportunity: List<PresetCandidate>
    ): String = locks.getOrPut(profileKey) { Mutex() }.withLock {
        require(submission.domain in SUPPORTED_DOMAINS)
        require(submission.localPayloadJson.length <= MAX_LOCAL_PAYLOAD_CHARS)
        require(submission.coarseFeaturesJson.length <= MAX_COARSE_FEATURE_CHARS)
        val fingerprint = sha256(submission.stableFingerprintSource)
        val interaction = interactionToken
            ?.let { dao.presetInteraction(profileKey, it.interactionId) }
            ?: dao.activePresetInteraction(profileKey, submission.domain.name)
        if (interaction != null) {
            return@withLock recordAssistedSubmissionLocked(
                profileKey = profileKey,
                submission = submission,
                snapshot = snapshot,
                fingerprint = fingerprint,
                interaction = interaction
            )
        }
        val existing = dao.recentLocalPresets(profileKey, submission.domain.name, 64).firstOrNull { it.fingerprint == fingerprint }
        val now = System.currentTimeMillis()
        val presetId = existing?.presetId ?: UUID.randomUUID().toString()
        dao.upsertLocalPreset(
            LocalParameterPresetEntity(
                presetId,
                profileKey,
                submission.domain.name,
                fingerprint,
                submission.localPayloadJson,
                submission.coarseFeaturesJson,
                existing?.createdAtEpochMs ?: now,
                now,
                (existing?.organicUseCount ?: 0) + 1,
                "ORGANIC_COMMIT",
                PRESET_SCHEMA_VERSION
            )
        )
        val contexts = contextKeys(snapshot)
        updateStats(profileKey, submission.domain, presetId, contexts, snapshot.epochDay, 1.0, 1.0)
        val trainingCandidates = candidatesAtOpportunity.ifEmpty {
            lastRankings[profileKey to submission.domain].orEmpty()
        }
        if (trainingCandidates.isEmpty()) return@withLock presetId
        val opportunityId = trainingCandidates.first().opportunityId
        val currentPresets = dao.recentLocalPresets(profileKey, submission.domain.name, MAX_CANDIDATES)
        val stats = dao.presetUsageStats(profileKey, submission.domain.name)
        val model = store.state(profileKey)
        val checkpoint = model.candidate ?: model.active
        val bootstrapCaptures = mutableListOf<BootstrapPresetCapture>()
        trainingCandidates.forEach { candidate ->
            val preset = currentPresets.firstOrNull { it.presetId == candidate.presetId }
                ?: dao.localPreset(profileKey, candidate.presetId)
                ?: return@forEach
            val features = candidate.featureVector.takeIf { it.size == PresetModelStateStore.INPUT_SIZE }
                ?: candidateFeatures(candidate.candidateOrdinal, preset, stats, contexts, snapshot)
            val label = candidate.presetId == presetId
            val statScore = candidate.statScore
            val tinyScore = candidate.tinyScore
            if (!candidate.promotionHoldout) {
                val trainingSample = PresetTrainingSampleEntity(
                    profileKey = profileKey,
                    domainId = submission.domain.name,
                    opportunityId = opportunityId,
                    candidateId = candidate.presetId,
                    features = BinaryCodec.floats(features),
                    label = label,
                    occurredEpochDay = snapshot.epochDay,
                    trainingCount = 0,
                    labelSource = "NATURAL_COMMIT_TRAINING",
                    sampleWeight = NATURAL_WEIGHT,
                    feedbackSource = PresetFeedbackSource.NATURAL_COMMIT.name,
                    weightConfigVersion = WEIGHT_CONFIG_VERSION,
                    naturalHoldoutEligible = true,
                    interactionId = null,
                    candidateOrdinal = candidate.candidateOrdinal
                )
                if (dao.insertPresetTrainingSample(trainingSample) != -1L) {
                    bootstrapCaptures += trainingSample.toBootstrapCapture(candidate.candidateOrdinal)
                }
            } else {
                bootstrapCaptures += BootstrapPresetCapture(
                        profileKey = profileKey,
                        domainId = submission.domain.name,
                        rawOpportunityId = opportunityId,
                        candidateOrdinal = candidate.candidateOrdinal,
                        features = BinaryCodec.floats(features),
                        label = label,
                        occurredEpochDay = snapshot.epochDay,
                        feedbackSource = PresetFeedbackSource.NATURAL_COMMIT.name,
                        sampleWeight = NATURAL_WEIGHT,
                        naturalHoldoutEligible = true
                    )
                val shadowEvaluation = PresetShadowEvaluationEntity(
                    profileKey = profileKey,
                    domainId = submission.domain.name,
                    opportunityId = opportunityId,
                    candidateId = candidate.presetId,
                    label = label,
                    statScore = statScore,
                    tinyScore = tinyScore,
                    recentBaselineScore = candidate.recentBaselineScore,
                    frequencyBaselineScore = candidate.frequencyBaselineScore,
                    tinyCheckpointId = candidate.checkpointId ?: checkpoint.id,
                    occurredEpochDay = snapshot.epochDay,
                    featureSchemaVersion = PRESET_FEATURE_SCHEMA_VERSION,
                    evaluationSource = "ORGANIC",
                    naturalHoldoutEligible = true
                    )
                dao.insertPresetShadowEvaluation(shadowEvaluation)
                telemetryAggregateStore.contributePreset(shadowEvaluation)
            }
        }
        runCatching { bootstrapTrainingDataManager?.capturePresetGroup(bootstrapCaptures) }
        if (trainingCandidates.any { !it.promotionHoldout }) pendingProfiles += profileKey
        presetId
    }

    suspend fun markRecommendationExposed(profileKey: String, candidate: PresetCandidate): PresetInteractionToken? =
        locks.getOrPut(profileKey) { Mutex() }.withLock {
            createExposedInteractionLocked(profileKey, candidate)
        }

    suspend fun applyRecommendation(profileKey: String, candidate: PresetCandidate): AppliedPreset? =
        locks.getOrPut(profileKey) { Mutex() }.withLock {
            val token = createExposedInteractionLocked(profileKey, candidate) ?: return@withLock null
            val current = dao.presetInteraction(profileKey, token.interactionId) ?: return@withLock null
            val transitioned = when (current.state) {
                PresetInteractionState.EXPOSED.name ->
                    dao.markPresetInteractionAppliedCas(profileKey, token.interactionId, System.currentTimeMillis()) == 1
                PresetInteractionState.APPLIED.name -> true
                else -> false
            }
            if (!transitioned) return@withLock null
            if (current.state == PresetInteractionState.EXPOSED.name) {
                insertFeedback(profileKey, candidate.opportunityId, candidate.presetId, "RECOMMENDED_PRESET_APPLIED")
                telemetryAggregateStore.recordPresetInteraction(profileKey, TelemetryPresetEvent.APPLIED)
            }
            AppliedPreset(candidate.localPayloadJson, token)
        }

    suspend fun expireInteraction(profileKey: String, token: PresetInteractionToken?) {
        locks.getOrPut(profileKey) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            if (token == null) return@withLock
            if (dao.expirePresetInteractionCas(profileKey, token.interactionId, now) == 1) {
                telemetryAggregateStore.recordPresetInteraction(profileKey, TelemetryPresetEvent.EXPIRED_WITHOUT_LABEL)
            }
        }
    }

    suspend fun recordRemovedRecommendation(
        profileKey: String,
        token: PresetInteractionToken,
        snapshot: ContextSnapshot
    ) = locks.getOrPut(profileKey) { Mutex() }.withLock {
        val interaction = dao.presetInteraction(profileKey, token.interactionId) ?: return@withLock
        if (interaction.state != PresetInteractionState.APPLIED.name) return@withLock
        recordAssistedResolutionLocked(
            profileKey = profileKey,
            interaction = interaction,
            snapshot = snapshot,
            state = PresetInteractionState.REMOVED,
            source = PresetFeedbackSource.ASSISTED_REMOVED,
            label = false,
            weight = ASSISTED_NEGATIVE_WEIGHT,
            resolutionFingerprint = null
        )
    }

    private suspend fun createExposedInteractionLocked(
        profileKey: String,
        candidate: PresetCandidate
    ): PresetInteractionToken? {
        dao.presetInteractionForCandidate(profileKey, candidate.opportunityId, candidate.presetId)?.let {
            return it.toToken()
        }
        val preset = dao.localPreset(profileKey, candidate.presetId) ?: return null
        val fingerprint = candidate.candidateFingerprint.ifBlank { preset.fingerprint }
        val now = System.currentTimeMillis()
        repeat(dao.expireActivePresetInteractions(profileKey, candidate.domain.name, now)) {
            telemetryAggregateStore.recordPresetInteraction(profileKey, TelemetryPresetEvent.EXPIRED_WITHOUT_LABEL)
        }
        val interaction = PresetRecommendationInteractionEntity(
            interactionId = UUID.randomUUID().toString(),
            profileKey = profileKey,
            domainId = candidate.domain.name,
            opportunityId = candidate.opportunityId,
            candidateId = candidate.presetId,
            candidateFingerprint = fingerprint,
            state = PresetInteractionState.EXPOSED.name,
            shownAtEpochMs = now,
            appliedAtEpochMs = null,
            resolvedAtEpochMs = null,
            resolutionFingerprint = null,
            feedbackWeight = null,
            checkpointId = candidate.checkpointId,
            exposureFeatures = candidate.featureVector
                .takeIf { it.size == PresetModelStateStore.INPUT_SIZE }
                ?.let(BinaryCodec::floats),
            candidateOrdinal = candidate.candidateOrdinal,
            occurredEpochDay = candidate.occurredEpochDay
        )
        if (dao.insertPresetInteraction(interaction) == -1L) {
            return dao.presetInteractionForCandidate(profileKey, candidate.opportunityId, candidate.presetId)?.toToken()
        }
        insertFeedback(profileKey, candidate.opportunityId, candidate.presetId, "RECOMMENDATION_EXPOSED")
        telemetryAggregateStore.recordPresetInteraction(profileKey, TelemetryPresetEvent.EXPOSED)
        return interaction.toToken()
    }

    private suspend fun recordAssistedSubmissionLocked(
        profileKey: String,
        submission: PresetSubmission,
        snapshot: ContextSnapshot,
        fingerprint: String,
        interaction: PresetRecommendationInteractionEntity
    ): String {
        val submittedPresetId = upsertAssistedPreset(profileKey, submission, fingerprint)
        if (interaction.state == PresetInteractionState.EXPOSED.name) {
            // The recommendation was visible but not applied. Visibility alone is not a label.
            if (dao.expirePresetInteractionCas(profileKey, interaction.interactionId, System.currentTimeMillis()) == 1) {
                telemetryAggregateStore.recordPresetInteraction(profileKey, TelemetryPresetEvent.EXPIRED_WITHOUT_LABEL)
            }
            return submittedPresetId
        }
        if (interaction.state != PresetInteractionState.APPLIED.name) return submittedPresetId
        val confirmed = fingerprint == interaction.candidateFingerprint
        recordAssistedResolutionLocked(
            profileKey = profileKey,
            interaction = interaction,
            snapshot = snapshot,
            state = if (confirmed) PresetInteractionState.QUERY_CONFIRMED else PresetInteractionState.REPLACED,
            source = if (confirmed) PresetFeedbackSource.ASSISTED_QUERY_CONFIRMED else PresetFeedbackSource.ASSISTED_REPLACED,
            label = confirmed,
            weight = if (confirmed) ASSISTED_POSITIVE_WEIGHT else ASSISTED_NEGATIVE_WEIGHT,
            resolutionFingerprint = fingerprint
        )
        return submittedPresetId
    }

    private suspend fun recordAssistedResolutionLocked(
        profileKey: String,
        interaction: PresetRecommendationInteractionEntity,
        snapshot: ContextSnapshot,
        state: PresetInteractionState,
        source: PresetFeedbackSource,
        label: Boolean,
        weight: Float,
        resolutionFingerprint: String?
    ) {
        val preset = dao.localPreset(profileKey, interaction.candidateId) ?: return
        val contexts = contextKeys(snapshot)
        val statsBefore = dao.presetUsageStats(profileKey, interaction.domainId)
        val index = interaction.candidateOrdinal
            ?: lastRankings[profileKey to SemanticDomain.valueOf(interaction.domainId)]
                ?.firstOrNull { it.presetId == interaction.candidateId }
                ?.candidateOrdinal
            ?: 0
        val features = interaction.exposureFeatures
            ?.let(BinaryCodec::floats)
            ?.takeIf { it.size == PresetModelStateStore.INPUT_SIZE }
            ?: candidateFeatures(index, preset, statsBefore, contexts, snapshot)
        val transitioned = dao.resolvePresetInteractionCas(
            profileKey = profileKey,
            interactionId = interaction.interactionId,
            resolvedState = state.name,
            resolvedAtEpochMs = System.currentTimeMillis(),
            resolutionFingerprint = resolutionFingerprint,
            feedbackWeight = weight
        ) == 1
        if (!transitioned) return
        updateStats(
            profileKey = profileKey,
            domain = SemanticDomain.valueOf(interaction.domainId),
            presetId = interaction.candidateId,
            contexts = contexts,
            epochDay = snapshot.epochDay,
            positiveDelta = if (label) weight.toDouble() else 0.0,
            exposureDelta = weight.toDouble()
        )
        val trainingSample = PresetTrainingSampleEntity(
                profileKey = profileKey,
                domainId = interaction.domainId,
                opportunityId = interaction.opportunityId,
                candidateId = interaction.candidateId,
                features = BinaryCodec.floats(features),
                label = label,
                occurredEpochDay = interaction.occurredEpochDay ?: snapshot.epochDay,
                trainingCount = 0,
                labelSource = source.name,
                sampleWeight = weight,
                feedbackSource = source.name,
                weightConfigVersion = WEIGHT_CONFIG_VERSION,
                naturalHoldoutEligible = false,
                interactionId = interaction.interactionId,
                candidateOrdinal = index
        )
        if (dao.insertPresetTrainingSample(trainingSample) != -1L) {
            runCatching { bootstrapTrainingDataManager?.capturePreset(trainingSample, index) }
        }
        insertFeedback(profileKey, interaction.opportunityId, interaction.candidateId, source.name)
        telemetryAggregateStore.recordPresetInteraction(
            profileKey,
            when (state) {
                PresetInteractionState.QUERY_CONFIRMED -> TelemetryPresetEvent.QUERY_CONFIRMED
                PresetInteractionState.REPLACED -> TelemetryPresetEvent.REPLACED
                PresetInteractionState.REMOVED -> TelemetryPresetEvent.REMOVED
                else -> TelemetryPresetEvent.EXPIRED_WITHOUT_LABEL
            },
            feedbackWeight = weight.toDouble()
        )
        pendingProfiles += profileKey
    }

    private suspend fun upsertAssistedPreset(
        profileKey: String,
        submission: PresetSubmission,
        fingerprint: String
    ): String {
        val existing = dao.recentLocalPresets(profileKey, submission.domain.name, 64)
            .firstOrNull { it.fingerprint == fingerprint }
        if (existing != null) return existing.presetId
        val now = System.currentTimeMillis()
        val presetId = UUID.randomUUID().toString()
        dao.upsertLocalPreset(
            LocalParameterPresetEntity(
                presetId = presetId,
                profileKey = profileKey,
                domainId = submission.domain.name,
                fingerprint = fingerprint,
                localPayloadJson = submission.localPayloadJson,
                coarseFeaturesJson = submission.coarseFeaturesJson,
                createdAtEpochMs = now,
                lastOrganicUsedAtEpochMs = null,
                organicUseCount = 0,
                source = "ASSISTED_COMMIT",
                schemaVersion = PRESET_SCHEMA_VERSION
            )
        )
        return presetId
    }

    private suspend fun insertFeedback(profileKey: String, decisionId: String, candidateId: String, type: String) {
        dao.insertTargetedFeedback(
            TargetedPredictionFeedbackEntity(
                UUID.randomUUID().toString(), profileKey, ModelTask.PRESET_RANKING.name,
                decisionId, candidateId, type, true, System.currentTimeMillis()
            )
        )
    }

    private fun PresetRecommendationInteractionEntity.toToken() = PresetInteractionToken(
        interactionId = interactionId,
        domain = SemanticDomain.valueOf(domainId),
        opportunityId = opportunityId,
        candidateId = candidateId,
        candidateFingerprint = candidateFingerprint
    )

    private fun PresetTrainingSampleEntity.toBootstrapCapture(candidateOrdinal: Int) =
        BootstrapPresetCapture(
            profileKey = profileKey,
            domainId = domainId,
            rawOpportunityId = opportunityId,
            candidateOrdinal = candidateOrdinal,
            features = features,
            label = label,
            occurredEpochDay = occurredEpochDay,
            feedbackSource = feedbackSource,
            sampleWeight = sampleWeight,
            naturalHoldoutEligible = naturalHoldoutEligible
        )

    suspend fun runIdleTrainingSlice(budgetMillis: Long): Boolean = withContext(trainerDispatcher) {
        val profileKey = pendingProfiles.firstOrNull() ?: return@withContext false
        val samples = dao.recentPresetTrainingSamples(profileKey, 512)
        val selected = PresetReplayPolicy.select(samples, MIN_TRAINING_SAMPLES, BATCH_SIZE, MAX_WEAK_ROWS)
        if (selected.isEmpty()) return@withContext false
        val state = store.state(profileKey)
        val parameters = state.training.parameters.deepCopy(state.optimizer.step)
        val optimizer = state.optimizer.deepCopy()
        val started = System.nanoTime()
        TinyMlpBackprop.trainBatch(
            parameters,
            optimizer,
            selected.map { BinaryCodec.floats(it.features) },
            selected.map { if (it.label) 1 else 0 }.toIntArray(),
            selected.map(PresetTrainingSampleEntity::sampleWeight).toFloatArray()
        )
        if (System.nanoTime() - started > budgetMillis.coerceAtMost(50) * 1_000_000L) return@withContext false
        val rowIds = selected.map(PresetTrainingSampleEntity::rowId)
        val batchId = sha256("preset|$profileKey|${state.training.revision}|${rowIds.joinToString(",")}")
        check(dao.insertTaskTrainingJournal(
            TaskTrainingBatchJournalEntity(batchId, profileKey, ModelTask.PRESET_RANKING.name, state.training.revision, rowIds.joinToString(","), "PREPARED", System.currentTimeMillis(), null)
        ) != -1L)
        val committed = store.commit(profileKey, state.training.revision, batchId, parameters, optimizer)
        check(dao.commitTaskTrainingJournal(batchId, System.currentTimeMillis()) == 1)
        dao.incrementPresetTrainingCounts(rowIds)
        evaluatePromotion(profileKey, committed)
        pendingProfiles.remove(profileKey)
        true
    }

    suspend fun clearProfile(profileKey: String) {
        pendingProfiles.remove(profileKey)
        lastRankings.keys.removeAll { it.first == profileKey }
        store.reset(profileKey)
    }
    fun resumeProfile(profileKey: String) { pendingProfiles += profileKey }

    fun sanitizedDiagnostics(profileKey: String): List<String> = lastRankings
        .filterKeys { it.first == profileKey }
        .flatMap { (key, candidates) ->
            candidates.map { candidate ->
                "${key.second.name} ${candidate.presetId.take(8)} ${candidate.reason} " +
                    "stat=${"%.3f".format(candidate.statScore)} " +
                    "tiny=${"%.3f".format(candidate.tinyScore)} " +
                    "effective=${"%.3f".format(candidate.effectiveScore)}"
            }
        }

    private suspend fun ensureTaskState(profileKey: String, model: PresetModelState): TaskModelStateEntity {
        val existing = dao.taskModelState(profileKey, ModelTask.PRESET_RANKING.name)
        if (existing != null &&
            existing.featureSchemaVersion == PRESET_FEATURE_SCHEMA_VERSION &&
            existing.outputSchemaVersion == PRESET_OUTPUT_SCHEMA_VERSION &&
            existing.activeCheckpointId == model.active.id
        ) return existing
        val checkpointRecovered = existing != null &&
            existing.featureSchemaVersion == PRESET_FEATURE_SCHEMA_VERSION &&
            existing.outputSchemaVersion == PRESET_OUTPUT_SCHEMA_VERSION
        return TaskModelStateEntity(
            profileKey, ModelTask.PRESET_RANKING.name, (existing?.modelGeneration ?: 0) + 1, PRESET_FEATURE_SCHEMA_VERSION, PRESET_OUTPUT_SCHEMA_VERSION,
            model.active.id, model.candidate?.id, model.training.revision, PromotionStage.SHADOW.name, 0f,
            0, 0, dao.naturalPresetTrainingSampleCount(profileKey), 0, SUPPORTED_DOMAINS.size, 1.0,
            if (checkpointRecovered) "LATCHED_STAT_ONLY" else "HEALTHY",
            when {
                existing == null -> "INITIALIZED_SHADOW"
                checkpointRecovered -> "CHECKPOINT_RECOVERED_STAT_ONLY"
                else -> "SCHEMA_INCOMPATIBLE_ISOLATED"
            },
            existing?.lastEvaluationSeq ?: 0,
            System.currentTimeMillis()
        ).also { dao.upsertTaskModelState(it) }
    }

    private suspend fun evaluatePromotion(profileKey: String, model: PresetModelState) {
        var state = ensureTaskState(profileKey, model)
        val evaluations = model.candidate?.let { candidate ->
            dao.presetShadowEvaluationsAfter(
                profileKey,
                candidate.id,
                state.lastEvaluationSeq,
                PROMOTION_WINDOW * REQUIRED_WINDOWS
            )
        }.orEmpty()
        val candidateReady = model.candidate != null && evaluations.size == PROMOTION_WINDOW * REQUIRED_WINDOWS
        val windows = if (candidateReady) evaluations.chunked(PROMOTION_WINDOW) else emptyList()
        val passes = windows.size == REQUIRED_WINDOWS && windows.all { window ->
            val tinyAccuracy = window.count { (it.tinyScore >= 0.5f) == it.label } / window.size.toDouble()
            val statAccuracy = window.count { (it.statScore >= 0.5f) == it.label } / window.size.toDouble()
            val recentAccuracy = window.count { (it.recentBaselineScore >= 0.5f) == it.label } / window.size.toDouble()
            val frequencyAccuracy = window.count { (it.frequencyBaselineScore >= 0.5f) == it.label } / window.size.toDouble()
            tinyAccuracy > maxOf(statAccuracy, recentAccuracy, frequencyAccuracy)
        }
        state = state.copy(
            candidateCheckpointId = model.candidate?.id,
            trainingRevision = model.training.revision,
            validSampleCount = dao.naturalPresetTrainingSampleCount(profileKey),
            consecutivePassingWindows = if (!candidateReady) state.consecutivePassingWindows else if (passes) state.consecutivePassingWindows + 1 else 0,
            consecutiveFailingWindows = if (!candidateReady) state.consecutiveFailingWindows else if (passes) 0 else state.consecutiveFailingWindows + 1,
            lastEvaluationSeq = if (candidateReady) evaluations.last().rowId else state.lastEvaluationSeq,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        if (passes && model.candidate != null) {
            val active = store.activateCandidate(profileKey)
            state = when (PromotionStage.valueOf(state.stage)) {
                PromotionStage.SHADOW -> state.copy(stage = PromotionStage.ELIGIBLE.name, mixedLambda = 0f)
                PromotionStage.ELIGIBLE -> state.copy(stage = PromotionStage.MIXED.name, mixedLambda = 0.25f)
                PromotionStage.MIXED -> if (state.consecutivePassingWindows >= 3) state.copy(stage = PromotionStage.PRIMARY.name, mixedLambda = 1f) else state.copy(mixedLambda = 0.5f)
                PromotionStage.PRIMARY -> state.copy(mixedLambda = 1f)
            }.copy(activeCheckpointId = active.active.id, candidateCheckpointId = null, modelGeneration = state.modelGeneration + 1, lastTransitionReason = "LOCAL_QUALITY_GATE_PASSED")
        } else if (candidateReady && !passes) {
            store.discardCandidate(profileKey)
            state = if (state.consecutiveFailingWindows >= 2 && state.stage != PromotionStage.SHADOW.name) {
                state.copy(stage = PromotionStage.SHADOW.name, mixedLambda = 0f, candidateCheckpointId = null, lastTransitionReason = "QUALITY_REGRESSION")
            } else {
                state.copy(candidateCheckpointId = null, lastTransitionReason = "CANDIDATE_QUALITY_GATE_FAILED")
            }
        }
        dao.upsertTaskModelState(state)
    }

    private suspend fun updateStats(
        profileKey: String,
        domain: SemanticDomain,
        presetId: String,
        contexts: PresetContexts,
        epochDay: Long,
        positiveDelta: Double,
        exposureDelta: Double
    ) {
        val existing = dao.presetUsageStats(profileKey, domain.name).associateBy { it.contextKey to it.presetId }
        listOf(contexts.global, contexts.time, contexts.business).forEach { context ->
            existing[context to presetId].let { current ->
                dao.upsertPresetUsageStat(
                    PresetUsageStatEntity(
                        profileKey,
                        domain.name,
                        context,
                        presetId,
                        (current?.positiveMass ?: 0.0) + positiveDelta,
                        (current?.exposureMass ?: 0.0) + exposureDelta,
                        epochDay
                    )
                )
            }
        }
    }

    private fun statisticalScore(presetId: String, stats: List<PresetUsageStatEntity>, contexts: PresetContexts): Float {
        val values = listOf(contexts.global to 0.35, contexts.time to 0.35, contexts.business to 0.30)
        return values.sumOf { (key, weight) ->
            val current = stats.firstOrNull { it.contextKey == key && it.presetId == presetId }
            weight * ((current?.positiveMass ?: 0.0) + 0.25) / ((current?.exposureMass ?: 0.0) + 0.5)
        }.toFloat().coerceIn(0f, 1f)
    }

    private fun candidateFeatures(index: Int, preset: LocalParameterPresetEntity, stats: List<PresetUsageStatEntity>, contexts: PresetContexts, snapshot: ContextSnapshot): FloatArray = FloatArray(PresetModelStateStore.INPUT_SIZE).also { features ->
        features[0] = statisticalScore(preset.presetId, stats, contexts)
        features[1] = 1f / (index + 1f)
        features[2] = (ln(preset.organicUseCount.coerceAtLeast(1).toDouble() + 1.0) / 5.0).toFloat().coerceIn(0f, 1f)
        features[3] = snapshot.minuteOfDay / 1439f
        features[4] = if (snapshot.dayType.name == "WEEKEND") 1f else 0f
        features[5] = snapshot.sessionDepth.coerceIn(0, 16) / 16f
        features[6] = snapshot.balanceBucket.ordinal / 5f
        features[7] = snapshot.examDistanceBucket.ordinal / 5f
        val domainHash = preset.domainId.fold(0) { acc, char -> acc * 31 + char.code } and Int.MAX_VALUE
        features[8 + domainHash % 4] = 1f
        val coarseHash = preset.coarseFeaturesJson.fold(0) { acc, char -> acc * 31 + char.code } and Int.MAX_VALUE
        features[12 + coarseHash % 4] = 1f
    }

    private fun topPreset(stats: List<PresetUsageStatEntity>, presets: List<LocalParameterPresetEntity>): LocalParameterPresetEntity? {
        val id = stats.maxByOrNull { it.positiveMass }?.presetId ?: return null
        return presets.firstOrNull { it.presetId == id }
    }

    private fun contextKeys(snapshot: ContextSnapshot) = PresetContexts(
        global = "global",
        time = "time:${snapshot.minuteOfDay / 240}:${snapshot.dayType}",
        business = "business:${snapshot.balanceBucket}:${snapshot.examDistanceBucket}"
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun AdamWState.deepCopy() = AdamWState(firstMoments.map(FloatArray::copyOf), secondMoments.map(FloatArray::copyOf), step)
    private data class PresetContexts(val global: String, val time: String, val business: String)
    private companion object {
        val SUPPORTED_DOMAINS = setOf(SemanticDomain.FREE_CLASSROOM, SemanticDomain.GRADE, SemanticDomain.LOST_FOUND, SemanticDomain.ELECTRICITY)
        const val MAX_CANDIDATES = 4
        const val PRESET_SCHEMA_VERSION = 1
        const val PRESET_FEATURE_SCHEMA_VERSION = 1
        const val PRESET_OUTPUT_SCHEMA_VERSION = 1
        const val MAX_LOCAL_PAYLOAD_CHARS = 8_192
        const val MAX_COARSE_FEATURE_CHARS = 1_024
        const val MIN_TRAINING_SAMPLES = 64
        const val BATCH_SIZE = 16
        const val MAX_WEAK_ROWS = BATCH_SIZE / 4
        const val NATURAL_WEIGHT = 1f
        const val ASSISTED_POSITIVE_WEIGHT = 0.20f
        const val ASSISTED_NEGATIVE_WEIGHT = 0.10f
        const val WEIGHT_CONFIG_VERSION = 1
        const val PROMOTION_WINDOW = 64
        const val REQUIRED_WINDOWS = 3
    }
}
