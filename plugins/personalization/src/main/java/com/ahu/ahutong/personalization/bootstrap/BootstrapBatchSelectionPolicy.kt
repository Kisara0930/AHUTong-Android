package com.ahu.ahutong.personalization.bootstrap

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.storage.BootstrapTrainingExampleEntity

internal object BootstrapBatchSelectionPolicy {
    fun select(task: String, rows: List<BootstrapTrainingExampleEntity>): List<BootstrapTrainingExampleEntity> {
        val natural = rows.filter { it.sampleWeight >= 0.999f }
        val weak = rows.filter { it.sampleWeight < 0.999f }
        val naturalSelected = when (task) {
            BootstrapTrainingTask.NEXT_ACTION.name -> cappedLabels(natural, AppActionCatalog.NONE_OUTPUT_ID, 192)
            BootstrapTrainingTask.JOURNEY_GOAL.name -> cappedLabels(natural, "NONE", 192)
            BootstrapTrainingTask.PRESET_RANKING.name -> wholePresetGroups(natural, 192)
            else -> emptyList()
        }
        if (naturalSelected.isEmpty()) return emptyList()
        val weakLimit = minOf(
            MAX_EXAMPLES_PER_BATCH / 4,
            MAX_EXAMPLES_PER_BATCH - naturalSelected.size,
            naturalSelected.size / 3
        )
        val weakSelected = when (task) {
            BootstrapTrainingTask.PRESET_RANKING.name -> wholePresetGroups(weak, weakLimit)
            else -> cappedLabels(
                weak,
                if (task == BootstrapTrainingTask.NEXT_ACTION.name) AppActionCatalog.NONE_OUTPUT_ID else "NONE",
                weakLimit
            )
        }
        return (naturalSelected + weakSelected).sortedBy { it.sequenceNo }.take(MAX_EXAMPLES_PER_BATCH)
    }

    fun invalidPresetRowIds(rows: List<BootstrapTrainingExampleEntity>): List<Long> =
        rows.groupBy(BootstrapTrainingExampleEntity::opportunityGroupId)
            .values
            .filter { group ->
                group.any { it.opportunityGroupId == null || it.candidateOrdinal == null } ||
                    group.map(BootstrapTrainingExampleEntity::candidateOrdinal).distinct().size != group.size ||
                    (group.all { it.sampleWeight >= 0.999f } && group.count { it.targetLabel == "1" } > 1)
            }
            .flatten()
            .map(BootstrapTrainingExampleEntity::rowId)

    fun reduce(task: String, rows: List<BootstrapTrainingExampleEntity>): List<BootstrapTrainingExampleEntity> {
        if (rows.isEmpty()) return rows
        if (task != BootstrapTrainingTask.PRESET_RANKING.name) {
            return rows.dropLast(maxOf(1, rows.size / 8))
        }
        val lastGroup = rows.last().opportunityGroupId
        return rows.filterNot { it.opportunityGroupId == lastGroup }
    }

    private fun cappedLabels(
        rows: List<BootstrapTrainingExampleEntity>,
        noneLabel: String,
        limit: Int
    ): List<BootstrapTrainingExampleEntity> {
        if (limit <= 0) return emptyList()
        val nonNone = rows.filter { it.targetLabel != noneLabel }
            .sortedBy { it.sequenceNo }
            .take(limit)
        if (nonNone.isEmpty()) return emptyList()
        val none = rows.filter { it.targetLabel == noneLabel }
            .sortedBy { it.sequenceNo }
            .take(minOf(nonNone.size, limit - nonNone.size))
        return (nonNone + none).sortedBy { it.sequenceNo }.take(limit)
    }

    private fun wholePresetGroups(
        rows: List<BootstrapTrainingExampleEntity>,
        limit: Int
    ): List<BootstrapTrainingExampleEntity> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<BootstrapTrainingExampleEntity>()
        rows.groupBy { it.opportunityGroupId }
            .values
            .sortedBy { group -> group.minOf { it.sequenceNo } }
            .forEach { group ->
                if (group.size <= limit - result.size) result += group.sortedBy { it.candidateOrdinal }
            }
        return result
    }
}
