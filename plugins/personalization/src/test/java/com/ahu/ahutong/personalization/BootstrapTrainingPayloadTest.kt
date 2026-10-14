package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.bootstrap.BOOTSTRAP_CONSENT_SCHEMA_VERSION
import com.ahu.ahutong.personalization.bootstrap.BootstrapExampleCompleteness
import com.ahu.ahutong.personalization.bootstrap.BootstrapTrainingBatchRequest
import com.ahu.ahutong.personalization.bootstrap.BootstrapTrainingExamplePayload
import com.ahu.ahutong.personalization.bootstrap.BootstrapTrainingPayloadValidator
import com.ahu.ahutong.personalization.bootstrap.BootstrapTrainingTask
import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.storage.BinaryCodec
import com.google.gson.Gson
import java.util.Base64
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BootstrapTrainingPayloadTest {
    @Test
    fun completeNextActionBatchIsModelReadyAndContainsNoLocalIdentifiers() {
        val request = batch(
            listOf(
                nextAction(sequence = 1, label = "OPEN_HOME", weight = 1f),
                nextAction(sequence = 2, label = "VIEW_SCHEDULE", weight = 1f),
                nextAction(sequence = 3, label = "OPEN_TOOLS", weight = 1f),
                nextAction(sequence = 4, label = AppActionCatalog.NONE_OUTPUT_ID, weight = 1f),
                nextAction(sequence = 5, label = "OPEN_HOME", weight = 0.25f, source = "SUGGESTION_ACCEPTED")
            )
        )

        BootstrapTrainingPayloadValidator.requireValid(request)
        val json = Gson().toJson(request)
        listOf(
            "profileKey",
            "decisionId",
            "journeyId",
            "presetId",
            "fingerprint",
            "localPayloadJson",
            "accountIdentifier",
            "deviceId"
        ).forEach { forbidden -> assertFalse(json.contains(forbidden, ignoreCase = true), forbidden) }
    }

    @Test
    fun targetedSemanticRecommendationCannotBecomeTrainingData() {
        val targeted = nextAction(1, "OPEN_HOME", 1f).copy(deliveryLane = "TARGETED")
        assertFailsWith<IllegalArgumentException> {
            BootstrapTrainingPayloadValidator.requireValid(batch(listOf(targeted)))
        }
    }

    @Test
    fun assistedRowsCannotExceedOneQuarterOfBatch() {
        val request = batch(
            listOf(
                nextAction(1, "OPEN_HOME", 1f),
                nextAction(2, "OPEN_HOME", 0.25f, "SUGGESTION_ACCEPTED")
            )
        )
        assertFailsWith<IllegalArgumentException> {
            BootstrapTrainingPayloadValidator.requireValid(request)
        }
    }

    @Test
    fun incompleteCurrentNextActionIsRejected() {
        val missingMask = nextAction(1, "OPEN_HOME", 1f).copy(availabilityMaskBase64 = null)
        assertFailsWith<IllegalArgumentException> {
            BootstrapTrainingPayloadValidator.requireValid(batch(listOf(missingMask)))
        }
    }

    @Test
    fun unavailableTargetIsRejectedBeforeItCanEnterTheDurableQueue() {
        val outputIndex = AppActionCatalog.outputIndex.getValue("OPEN_HOME")
        val invalidMask = BooleanArray(AppActionCatalog.outputIds.size) { true }.also { it[outputIndex] = false }
        val example = nextAction(1, "OPEN_HOME", 1f).copy(
            availabilityMaskBase64 = Base64.getEncoder().encodeToString(BinaryCodec.booleans(invalidMask))
        )

        assertFailsWith<IllegalArgumentException> {
            BootstrapTrainingPayloadValidator.requireValid(batch(listOf(example)))
        }
    }

    @Test
    fun outOfSchemaFeatureIsRejectedBeforeItCanEnterTheDurableQueue() {
        val features = FloatArray(FeatureExtractor.INPUT_DIMENSION).also { it[40] = 10f }
        val example = nextAction(1, "OPEN_HOME", 1f).copy(
            featuresBase64 = Base64.getEncoder().encodeToString(BinaryCodec.floats(features))
        )

        assertFailsWith<IllegalArgumentException> {
            BootstrapTrainingPayloadValidator.requireValid(batch(listOf(example)))
        }
    }

    @Test
    fun naturalPresetGroupAllowsNoMatchButRejectsMultipleMatches() {
        val noMatch = batch(listOf(preset(1, "0", 0), preset(2, "0", 1)))
        BootstrapTrainingPayloadValidator.requireValid(noMatch)

        assertFailsWith<IllegalArgumentException> {
            BootstrapTrainingPayloadValidator.requireValid(
                batch(listOf(preset(1, "1", 0), preset(2, "1", 1)))
            )
        }
    }

    private fun batch(examples: List<BootstrapTrainingExamplePayload>) = BootstrapTrainingBatchRequest(
        batchId = UUID.randomUUID().toString(),
        participantId = UUID.randomUUID().toString(),
        consentLifecycleId = UUID.randomUUID().toString(),
        consentSchemaVersion = BOOTSTRAP_CONSENT_SCHEMA_VERSION,
        revocationCapabilityHash = "a".repeat(64),
        appVersionCode = 321,
        examples = examples
    )

    private fun nextAction(
        sequence: Long,
        label: String,
        weight: Float,
        source: String = "ORGANIC_ACTION"
    ) = BootstrapTrainingExamplePayload(
        exampleId = UUID.randomUUID().toString(),
        sequenceNo = sequence,
        task = BootstrapTrainingTask.NEXT_ACTION.name,
        completeness = BootstrapExampleCompleteness.COMPLETE.name,
        featureSchemaVersion = FeatureExtractor.FEATURE_SCHEMA_VERSION,
        outputSchemaVersion = AppActionCatalog.OUTPUT_SCHEMA_VERSION,
        actionCatalogVersion = AppActionCatalog.ACTION_CATALOG_VERSION,
        featuresBase64 = Base64.getEncoder().encodeToString(
            BinaryCodec.floats(FloatArray(FeatureExtractor.INPUT_DIMENSION))
        ),
        availabilityMaskBase64 = Base64.getEncoder().encodeToString(
            BinaryCodec.booleans(BooleanArray(AppActionCatalog.outputIds.size) { true })
        ),
        targetLabel = label,
        feedbackSource = source,
        sampleWeight = weight,
        deliveryLane = "ORDINARY_NEXT_ACTION",
        domainId = null,
        opportunityGroupId = null,
        candidateOrdinal = null,
        journeyLengthBucket = null,
        naturalHoldoutEligible = source == "ORGANIC_ACTION",
        occurredEpochDay = 20_000,
        historical = false
    )

    private fun preset(sequence: Long, label: String, ordinal: Int) = BootstrapTrainingExamplePayload(
        exampleId = UUID.randomUUID().toString(),
        sequenceNo = sequence,
        task = BootstrapTrainingTask.PRESET_RANKING.name,
        completeness = BootstrapExampleCompleteness.COMPLETE.name,
        featureSchemaVersion = 1,
        outputSchemaVersion = 1,
        actionCatalogVersion = AppActionCatalog.ACTION_CATALOG_VERSION,
        featuresBase64 = Base64.getEncoder().encodeToString(BinaryCodec.floats(FloatArray(16))),
        availabilityMaskBase64 = null,
        targetLabel = label,
        feedbackSource = "NATURAL_COMMIT",
        sampleWeight = 1f,
        deliveryLane = null,
        domainId = "GRADE",
        opportunityGroupId = "a".repeat(64),
        candidateOrdinal = ordinal,
        journeyLengthBucket = null,
        naturalHoldoutEligible = true,
        occurredEpochDay = 20_000,
        historical = false
    )
}
