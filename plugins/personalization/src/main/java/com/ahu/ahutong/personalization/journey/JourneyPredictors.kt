package com.ahu.ahutong.personalization.journey

import com.ahu.ahutong.personalization.context.PredictionInput
import com.ahu.ahutong.personalization.inference.TinyMlpMath
import com.ahu.ahutong.personalization.storage.BehaviorDao
import com.ahu.ahutong.personalization.storage.JourneyActionStatEntity
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class JourneyProbabilityVector(
    val outputIds: List<String>,
    val probabilities: FloatArray,
    val inferenceNanos: Long,
    val modelVersion: Int,
    val checkpointId: String? = null
) {
    init {
        require(outputIds.size == probabilities.size)
        require(probabilities.all { it.isFinite() && it in 0f..1f })
        require(kotlin.math.abs(probabilities.sum() - 1f) < 1e-3f)
    }

    fun probability(outputId: String): Float = probabilities.getOrElse(outputIds.indexOf(outputId)) { 0f }
    fun rankedIndices(): List<Int> = probabilities.indices.sortedWith(compareByDescending<Int> { probabilities[it] }.thenBy { it })
    fun asMap(): Map<String, Float> = outputIds.indices.associate { outputIds[it] to probabilities[it] }
}

class JourneyFrequencyPredictor constructor(private val dao: BehaviorDao) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun predict(input: PredictionInput): JourneyProbabilityVector {
        val started = System.nanoTime()
        val stats = dao.journeyActionStats(input.profileKey)
        val byContext = stats.groupBy(JourneyActionStatEntity::contextKey)
        val keys = contextKeys(input)
        val scores = DoubleArray(JourneyGoalCatalog.outputIds.size)
        JourneyGoalCatalog.outputIds.forEachIndexed { index, target ->
            var weightedLog = 0.0
            var weight = 0.0
            keys.forEach { (key, componentWeight) ->
                val values = byContext[key].orEmpty()
                val total = values.firstOrNull { it.targetActionId == TOTAL }?.decayedExposure(input.snapshot.epochDay) ?: 0.0
                val positive = values.firstOrNull { it.targetActionId == target }?.decayedPositive(input.snapshot.epochDay) ?: 0.0
                weightedLog += componentWeight * ln(((positive + SMOOTHING) /
                    (total + SMOOTHING * JourneyGoalCatalog.outputIds.size)).coerceAtLeast(1e-12))
                weight += componentWeight
            }
            scores[index] = if (weight == 0.0) 1.0 else exp(weightedLog / weight)
        }
        if (stats.isEmpty()) scores[JourneyGoalCatalog.outputIndex.getValue(JourneyGoalCatalog.NONE_OUTPUT_ID)] *= 3.0
        return vector(scores, System.nanoTime() - started, 1)
    }

    suspend fun update(input: PredictionInput, targetActionId: String) {
        require(targetActionId in JourneyGoalCatalog.outputIndex)
        locks.getOrPut(input.profileKey) { Mutex() }.withLock {
            val today = input.snapshot.epochDay
            val existing = dao.journeyActionStats(input.profileKey).associateBy { it.contextKey to it.targetActionId }
            contextKeys(input).map { it.first }.distinct().forEach { key ->
                val total = existing[key to TOTAL]
                dao.upsertJourneyActionStat(
                    JourneyActionStatEntity(input.profileKey, key, TOTAL, 0.0, (total?.decayedExposure(today) ?: 0.0) + 1.0, today)
                )
                val positive = existing[key to targetActionId]
                dao.upsertJourneyActionStat(
                    JourneyActionStatEntity(input.profileKey, key, targetActionId, (positive?.decayedPositive(today) ?: 0.0) + 1.0, 0.0, today)
                )
            }
        }
    }

    internal fun contextKeys(input: PredictionInput): List<Pair<String, Double>> {
        val snapshot = input.snapshot
        return buildList {
            add("journey:global" to 0.18)
            add("journey:time:${snapshot.minuteOfDay / 240}:${snapshot.dayType}" to 0.20)
            add("journey:route:${snapshot.route ?: "NONE"}" to 0.18)
            add("journey:recent:${snapshot.recentActions.takeLast(3).joinToString(",") { it.stableId }}" to 0.12)
            add("journey:business:${snapshot.balanceBucket}:${snapshot.examDistanceBucket}" to 0.10)
            snapshot.semanticContext?.let {
                add("journey:event:${it.eventFamily}:${it.semanticId}" to 0.30)
                add("journey:event_time:${it.eventFamily}:${snapshot.minuteOfDay / 240}" to 0.20)
            }
        }
    }

    private fun JourneyActionStatEntity.decayedPositive(today: Long): Double =
        positiveMass * 2.0.pow(-((today - updatedAtEpochDay).coerceAtLeast(0)).toDouble() / HALF_LIFE_DAYS)
    private fun JourneyActionStatEntity.decayedExposure(today: Long): Double =
        exposureMass * 2.0.pow(-((today - updatedAtEpochDay).coerceAtLeast(0)).toDouble() / HALF_LIFE_DAYS)

    private companion object {
        const val TOTAL = "__TOTAL__"
        const val HALF_LIFE_DAYS = 30.0
        const val SMOOTHING = 0.25
    }
}

class TinyJourneyMlpPredictor constructor(private val store: JourneyModelStateStore) {
    suspend fun predict(input: PredictionInput): JourneyProbabilityVector = withContext(Dispatchers.Default) {
        val state = store.state(input.profileKey)
        val checkpoint = state.candidate ?: state.active
        val started = System.nanoTime()
        val probabilities = TinyMlpMath.forward(checkpoint.parameters, input.features.copy()).probabilities
        JourneyProbabilityVector(
            JourneyGoalCatalog.outputIds,
            probabilities,
            System.nanoTime() - started,
            modelVersion = 1,
            checkpointId = checkpoint.checkpointId
        )
    }
}

private fun vector(scores: DoubleArray, inferenceNanos: Long, modelVersion: Int): JourneyProbabilityVector {
    val sum = scores.sum().coerceAtLeast(1e-12)
    return JourneyProbabilityVector(
        JourneyGoalCatalog.outputIds,
        FloatArray(scores.size) { (scores[it] / sum).toFloat() },
        inferenceNanos,
        modelVersion
    )
}
