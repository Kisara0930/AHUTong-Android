package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.preset.PresetFeedbackSource
import com.ahu.ahutong.personalization.preset.PresetReplayPolicy
import com.ahu.ahutong.personalization.storage.PresetTrainingSampleEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetReplayPolicyTest {
    @Test
    fun weakRowsCannotSatisfyNaturalGateOrFillNaturalShortfall() {
        val weakOnly = (1L..128L).map { sample(it, natural = false, weight = 0.2f, label = true) }
        assertTrue(PresetReplayPolicy.select(weakOnly, 64, 16, 4).isEmpty())

        val insufficientNatural = (1L..63L).map { sample(it, natural = true, weight = 1f, label = it == 1L) }
        assertTrue(PresetReplayPolicy.select(insufficientNatural + weakOnly, 64, 16, 4).isEmpty())
    }

    @Test
    fun naturalRowsStayPrimaryAndWeakRowsRespectRowAndEffectiveMassCaps() {
        val natural = (1L..64L).map { sample(it, natural = true, weight = 1f, label = it == 1L) }
        val weak = (65L..96L).map { sample(it, natural = false, weight = 1f, label = true) }

        val selected = PresetReplayPolicy.select(natural + weak, 64, 16, 4)
        val selectedNatural = selected.filter(PresetTrainingSampleEntity::naturalHoldoutEligible)
        val selectedWeak = selected.filterNot(PresetTrainingSampleEntity::naturalHoldoutEligible)
        val naturalMass = selectedNatural.sumOf { it.sampleWeight.toDouble() }
        val weakMass = selectedWeak.sumOf { it.sampleWeight.toDouble() }

        assertEquals(12, selectedNatural.size)
        assertTrue(selectedWeak.size <= 4)
        assertTrue(selectedWeak.size.toDouble() / selected.size <= 0.25)
        assertTrue(weakMass / (naturalMass + weakMass) <= 0.20 + 1e-9)
    }

    private fun sample(rowId: Long, natural: Boolean, weight: Float, label: Boolean) =
        PresetTrainingSampleEntity(
            rowId = rowId,
            profileKey = "profile",
            domainId = "GRADE",
            opportunityId = "opportunity-$rowId",
            candidateId = "candidate-$rowId",
            features = byteArrayOf(),
            label = label,
            occurredEpochDay = 20_000,
            trainingCount = 0,
            labelSource = if (natural) "NATURAL_COMMIT_HOLDOUT" else "ASSISTED",
            sampleWeight = weight,
            feedbackSource = if (natural) {
                PresetFeedbackSource.NATURAL_COMMIT.name
            } else {
                PresetFeedbackSource.ASSISTED_QUERY_CONFIRMED.name
            },
            weightConfigVersion = 1,
            naturalHoldoutEligible = natural,
            interactionId = if (natural) null else "interaction-$rowId"
        )
}
