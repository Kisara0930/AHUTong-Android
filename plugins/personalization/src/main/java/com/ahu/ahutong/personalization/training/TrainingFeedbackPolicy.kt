package com.ahu.ahutong.personalization.training

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.storage.TrainingSampleEntity
import kotlin.math.min

internal object TrainingFeedbackPolicy {
    const val ORGANIC_ACTION = "ORGANIC_ACTION"
    const val INTERVENTION_FREE_TIMEOUT = "INTERVENTION_FREE_TIMEOUT"
    const val SUGGESTION_ACCEPTED = "SUGGESTION_ACCEPTED"
    const val SUGGESTION_POSITIVE_WEIGHT = 0.25f

    fun isNatural(labelSource: String): Boolean = labelSource == ORGANIC_ACTION ||
        labelSource == INTERVENTION_FREE_TIMEOUT

    fun isAcceptedSuggestion(labelSource: String): Boolean = labelSource == SUGGESTION_ACCEPTED

    fun isTrainable(labelSource: String): Boolean = isNatural(labelSource) || isAcceptedSuggestion(labelSource)

    fun sampleWeight(labelSource: String): Float = when {
        isNatural(labelSource) -> 1f
        isAcceptedSuggestion(labelSource) -> SUGGESTION_POSITIVE_WEIGHT
        else -> 0f
    }
}

internal object TrainingReplayPolicy {
    fun select(
        values: List<TrainingSampleEntity>,
        size: Int,
        maximumWeakRows: Int
    ): List<TrainingSampleEntity> {
        require(size > 0)
        require(maximumWeakRows in 0..size)
        val naturalPool = values.filter { TrainingFeedbackPolicy.isNatural(it.labelSource) }
        val weakPool = values.filter { TrainingFeedbackPolicy.isAcceptedSuggestion(it.labelSource) }
        val weakTarget = minOf(maximumWeakRows, weakPool.size)
        val naturalTarget = size - weakTarget
        val natural = balancedByTarget(naturalPool, naturalTarget)
        if (natural.size < naturalTarget) return emptyList()
        val weak = balancedByTarget(weakPool, weakTarget)
        return (natural + weak).distinctBy(TrainingSampleEntity::rowId).take(size)
    }

    private fun balancedByTarget(values: List<TrainingSampleEntity>, size: Int): List<TrainingSampleEntity> {
        if (size == 0) return emptyList()
        val nonNoneGroups = values.filter { it.targetActionId != AppActionCatalog.NONE_OUTPUT_ID }
            .groupBy(TrainingSampleEntity::targetActionId)
            .values
            .map { group ->
                ArrayDeque(group.sortedWith(
                    compareBy<TrainingSampleEntity> { it.trainingCount }
                        .thenByDescending { it.replayPriority }
                        .thenByDescending { it.rowId }
                ))
            }
            .sortedBy { it.size }
        val none = values.filter { it.targetActionId == AppActionCatalog.NONE_OUTPUT_ID }
            .sortedWith(
                compareBy<TrainingSampleEntity> { it.trainingCount }
                    .thenByDescending { it.replayPriority }
                    .thenByDescending { it.rowId }
            )
        val result = ArrayList<TrainingSampleEntity>(size)
        val nonNoneTarget = size - min(none.size, size / 2)
        while (result.size < nonNoneTarget && nonNoneGroups.any(ArrayDeque<TrainingSampleEntity>::isNotEmpty)) {
            nonNoneGroups.forEach { group ->
                if (result.size < nonNoneTarget && group.isNotEmpty()) result += group.removeFirst()
            }
        }
        result += none.take(min(size / 2, size - result.size))
        if (result.size < size) {
            result += nonNoneGroups.flatMap(ArrayDeque<TrainingSampleEntity>::toList).take(size - result.size)
        }
        return result.distinctBy(TrainingSampleEntity::rowId).take(size)
    }
}
