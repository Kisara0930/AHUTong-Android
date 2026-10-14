package com.ahu.ahutong.personalization.inference

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.context.PredictionInput
import com.ahu.ahutong.personalization.model.ModelStateStore
import com.ahu.ahutong.personalization.storage.ActionStatEntity
import com.ahu.ahutong.personalization.storage.BehaviorDao
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DecayedFrequencyPredictor constructor(
    private val dao: BehaviorDao
) : NextActionPredictor {
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun predict(input: PredictionInput): NextActionProbabilityVector {
        val started = System.nanoTime()
        val stats = dao.actionStats(input.profileKey)
        val today = input.snapshot.epochDay
        val byContext = stats.groupBy(ActionStatEntity::contextKey)
        val scores = DoubleArray(AppActionCatalog.outputIds.size)
        val components = contextKeys(input)
        AppActionCatalog.outputIds.forEachIndexed { index, actionId ->
            var weightedLog = 0.0
            var weightSum = 0.0
            components.forEach { (key, componentWeight) ->
                val values = byContext[key].orEmpty()
                val total = values.firstOrNull { it.actionId == TOTAL }?.decayedExposure(today) ?: 0.0
                val positive = values.firstOrNull { it.actionId == actionId }?.decayedPositive(today) ?: 0.0
                val probability = (positive + SMOOTHING) /
                    (total + SMOOTHING * AppActionCatalog.outputIds.size)
                weightedLog += componentWeight * ln(probability.coerceAtLeast(1e-12))
                weightSum += componentWeight
            }
            scores[index] = if (weightSum > 0) exp(weightedLog / weightSum) else 1.0
        }
        // During cold start NONE remains conservative, without blocking cheap cache warming rules.
        if (stats.isEmpty()) {
            scores.fill(1.0)
            scores[AppActionCatalog.outputIndex.getValue(AppActionCatalog.NONE_OUTPUT_ID)] = 3.0
        }
        val sum = scores.sum().coerceAtLeast(1e-12)
        return NextActionProbabilityVector(
            outputIds = AppActionCatalog.outputIds,
            probabilities = FloatArray(scores.size) { (scores[it] / sum).toFloat() },
            inferenceNanos = System.nanoTime() - started,
            modelVersion = MODEL_VERSION
        )
    }

    suspend fun update(input: PredictionInput, targetOutputId: String, weight: Double = 1.0) {
        require(targetOutputId in AppActionCatalog.outputIndex)
        require(weight.isFinite() && weight > 0.0 && weight <= 1.0)
        locks.getOrPut(input.profileKey) { Mutex() }.withLock {
            val today = input.snapshot.epochDay
            val existing = dao.actionStats(input.profileKey).associateBy { it.contextKey to it.actionId }
            contextKeys(input).map(Pair<String, Double>::first).distinct().forEach { key ->
                val totalKey = key to TOTAL
                val total = existing[totalKey]
                dao.upsertActionStat(
                    ActionStatEntity(
                        input.profileKey,
                        key,
                        TOTAL,
                        0.0,
                        (total?.decayedExposure(today) ?: 0.0) + weight,
                        today
                    )
                )
                val actionKey = key to targetOutputId
                val action = existing[actionKey]
                dao.upsertActionStat(
                    ActionStatEntity(
                        input.profileKey,
                        key,
                        targetOutputId,
                        (action?.decayedPositive(today) ?: 0.0) + weight,
                        0.0,
                        today
                    )
                )
            }
        }
    }

    override suspend fun reset(profileKey: String) = Unit

    internal fun contextKeys(input: PredictionInput): List<Pair<String, Double>> {
        val snapshot = input.snapshot
        val result = mutableListOf(
            "global" to 0.24,
            "time:${snapshot.minuteOfDay / 60}:${snapshot.dayType}" to 0.26,
            "previous:${snapshot.previousAction?.stableId ?: "NONE"}" to 0.22,
            "recent:${snapshot.recentActions.takeLast(3).joinToString(",") { it.stableId }}" to 0.18,
            "business:${snapshot.balanceBucket}:${snapshot.examDistanceBucket}" to 0.10
        )
        snapshot.semanticContext?.let { semantic ->
            result += "mutation:${semantic.semanticId}" to 0.30
            result += "mutation_route:${semantic.semanticId}:${snapshot.route ?: "NONE"}" to 0.24
            result += "mutation_time:${semantic.semanticId}:${snapshot.minuteOfDay / 240}" to 0.18
        }
        snapshot.contentContext?.let { content ->
            result += "content:${content.domain}:${content.state}" to 0.20
        }
        return result
    }

    private fun ActionStatEntity.decayedPositive(today: Long): Double =
        positiveMass * 2.0.pow(-((today - updatedAtEpochDay).coerceAtLeast(0)).toDouble() / HALF_LIFE_DAYS)

    private fun ActionStatEntity.decayedExposure(today: Long): Double =
        exposureMass * 2.0.pow(-((today - updatedAtEpochDay).coerceAtLeast(0)).toDouble() / HALF_LIFE_DAYS)

    private companion object {
        const val TOTAL = "__TOTAL__"
        const val MODEL_VERSION = 1
        const val HALF_LIFE_DAYS = 30.0
        const val SMOOTHING = 0.25
    }
}

class RecentActionBaselinePredictor constructor() : NextActionPredictor {
    override suspend fun predict(input: PredictionInput): NextActionProbabilityVector {
        val started = System.nanoTime()
        val scores = DoubleArray(AppActionCatalog.outputIds.size) { 0.2 }
        input.snapshot.recentActions.takeLast(8).reversed().forEachIndexed { age, action ->
            AppActionCatalog.outputIndex(action)?.let { scores[it] += 4.0 / (age + 1.0) }
        }
        scores[AppActionCatalog.outputIndex.getValue(AppActionCatalog.NONE_OUTPUT_ID)] += 0.5
        return vector(scores, System.nanoTime() - started, MODEL_VERSION)
    }

    override suspend fun reset(profileKey: String) = Unit

    private companion object { const val MODEL_VERSION = 1 }
}

class TimeBucketFrequencyBaselinePredictor constructor(
    private val dao: BehaviorDao
) : NextActionPredictor {
    override suspend fun predict(input: PredictionInput): NextActionProbabilityVector {
        val started = System.nanoTime()
        val key = "time:${input.snapshot.minuteOfDay / 60}:${input.snapshot.dayType}"
        val stats = dao.actionStats(input.profileKey).filter { it.contextKey == key }
        val total = stats.firstOrNull { it.actionId == "__TOTAL__" }?.exposureMass ?: 0.0
        val scores = DoubleArray(AppActionCatalog.outputIds.size) { index ->
            val count = stats.firstOrNull { it.actionId == AppActionCatalog.outputIds[index] }?.positiveMass ?: 0.0
            (count + 0.25) / (total + 0.25 * AppActionCatalog.outputIds.size)
        }
        return vector(scores, System.nanoTime() - started, MODEL_VERSION)
    }

    override suspend fun reset(profileKey: String) = Unit

    private companion object { const val MODEL_VERSION = 1 }
}

class TinyMlpPredictor constructor(
    private val stateStore: ModelStateStore
) : NextActionPredictor {
    override suspend fun predict(input: PredictionInput): NextActionProbabilityVector = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val checkpoint = stateStore.activeCheckpoint(input.profileKey)
        val pass = TinyMlpMath.forward(checkpoint.parameters, input.features.copy())
        NextActionProbabilityVector(
            AppActionCatalog.outputIds,
            pass.probabilities,
            System.nanoTime() - started,
            TinyMlpParameters.MODEL_VERSION
        )
    }

    suspend fun predictCandidate(input: PredictionInput): NextActionProbabilityVector? = withContext(Dispatchers.Default) {
        val candidate = stateStore.state(input.profileKey).candidate ?: return@withContext null
        val started = System.nanoTime()
        NextActionProbabilityVector(
            AppActionCatalog.outputIds,
            TinyMlpMath.forward(candidate.parameters, input.features.copy()).probabilities,
            System.nanoTime() - started,
            TinyMlpParameters.MODEL_VERSION
        )
    }

    override suspend fun reset(profileKey: String) = stateStore.reset(profileKey)
}

private fun vector(scores: DoubleArray, inferenceNanos: Long, modelVersion: Int): NextActionProbabilityVector {
    val sum = scores.sum().coerceAtLeast(1e-12)
    return NextActionProbabilityVector(
        AppActionCatalog.outputIds,
        FloatArray(scores.size) { (scores[it] / sum).toFloat() },
        inferenceNanos,
        modelVersion
    )
}
