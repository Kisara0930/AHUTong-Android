package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.bootstrap.BootstrapBatchSelectionPolicy
import com.ahu.ahutong.personalization.bootstrap.BootstrapTrainingTask
import com.ahu.ahutong.personalization.storage.BootstrapTrainingExampleEntity
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class BootstrapBatchSelectionPolicyTest {
    @Test
    fun singleNaturalLabelCanBeBatchedWithoutSyntheticBalancing() {
        val selected = BootstrapBatchSelectionPolicy.select(
            BootstrapTrainingTask.NEXT_ACTION.name,
            listOf(row(1, "OPEN_HOME"))
        )

        assertEquals(listOf("OPEN_HOME"), selected.map { it.targetLabel })
    }

    @Test
    fun noneRowsAreCappedWithoutChangingNaturalLabelDistribution() {
        val rows = listOf(row(1, "OPEN_HOME")) +
            (2L..20L).map { row(it, AppActionCatalog.NONE_OUTPUT_ID) }

        val selected = BootstrapBatchSelectionPolicy.select(BootstrapTrainingTask.NEXT_ACTION.name, rows)

        assertEquals(1, selected.count { it.targetLabel == "OPEN_HOME" })
        assertEquals(1, selected.count { it.targetLabel == AppActionCatalog.NONE_OUTPUT_ID })
    }

    @Test
    fun assistedRowsRemainAtMostOneQuarter() {
        val rows = (1L..12L).map { row(it, "OPEN_HOME") } +
            (13L..30L).map { row(it, "VIEW_SCHEDULE", weight = 0.25f) }

        val selected = BootstrapBatchSelectionPolicy.select(BootstrapTrainingTask.NEXT_ACTION.name, rows)

        assertTrue(selected.count { it.sampleWeight < 1f } <= selected.size / 4)
    }

    @Test
    fun presetReductionNeverSplitsAnOpportunity() {
        val rows = listOf(
            row(1, "1", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 0),
            row(2, "0", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 1),
            row(3, "1", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "b", ordinal = 0),
            row(4, "0", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "b", ordinal = 1)
        )

        val reduced = BootstrapBatchSelectionPolicy.reduce(BootstrapTrainingTask.PRESET_RANKING.name, rows)

        assertEquals(setOf("a"), reduced.mapNotNull { it.opportunityGroupId }.toSet())
        assertEquals(2, reduced.size)
    }

    @Test
    fun missingPresetOrdinalQuarantinesWholeOpportunity() {
        val rows = listOf(
            row(1, "1", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = null),
            row(2, "0", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 1)
        )

        assertEquals(setOf(1L, 2L), BootstrapBatchSelectionPolicy.invalidPresetRowIds(rows).toSet())
    }

    @Test
    fun naturalPresetOpportunityMayHaveNoPositiveWhenUserCreatesNewParameters() {
        val rows = listOf(
            row(1, "0", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 0),
            row(2, "0", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 1)
        )

        assertTrue(BootstrapBatchSelectionPolicy.invalidPresetRowIds(rows).isEmpty())
    }

    @Test
    fun multipleNaturalPresetPositivesQuarantineWholeOpportunity() {
        val rows = listOf(
            row(1, "1", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 0),
            row(2, "1", task = BootstrapTrainingTask.PRESET_RANKING.name, group = "a", ordinal = 1)
        )

        assertEquals(setOf(1L, 2L), BootstrapBatchSelectionPolicy.invalidPresetRowIds(rows).toSet())
    }

    private fun row(
        sequence: Long,
        label: String,
        weight: Float = 1f,
        task: String = BootstrapTrainingTask.NEXT_ACTION.name,
        group: String? = null,
        ordinal: Int? = null
    ) = BootstrapTrainingExampleEntity(
        rowId = sequence,
        exampleId = UUID.randomUUID().toString(),
        profileKey = "profile",
        consentLifecycleId = "lifecycle",
        participantId = "participant",
        sequenceNo = sequence,
        task = task,
        completeness = "COMPLETE",
        featureSchemaVersion = 1,
        outputSchemaVersion = 1,
        actionCatalogVersion = 1,
        features = byteArrayOf(),
        availabilityMask = null,
        targetLabel = label,
        feedbackSource = if (weight < 1f) "SUGGESTION_ACCEPTED" else "ORGANIC_ACTION",
        sampleWeight = weight,
        deliveryLane = null,
        domainId = null,
        opportunityGroupId = group,
        candidateOrdinal = ordinal,
        journeyLengthBucket = null,
        naturalHoldoutEligible = weight == 1f,
        occurredEpochDay = 20_000,
        historical = false,
        state = "PENDING",
        batchId = null,
        createdAtEpochMs = sequence
    )
}
