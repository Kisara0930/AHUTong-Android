package com.ahu.ahutong.personalization.bootstrap

import com.ahu.ahutong.personalization.journey.JourneyTrainingLabelPolicy
import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.journey.JourneyGoalCatalog
import com.ahu.ahutong.personalization.preset.PresetModelStateStore
import com.ahu.ahutong.personalization.storage.BinaryCodec
import com.ahu.ahutong.personalization.storage.BootstrapTrainingExampleEntity
import java.util.UUID
import java.util.Base64

enum class BootstrapTrainingTask { NEXT_ACTION, JOURNEY_GOAL, PRESET_RANKING }
enum class BootstrapExampleCompleteness { COMPLETE, LEGACY_PARTIAL }

data class BootstrapTrainingExamplePayload(
    val exampleId: String,
    val sequenceNo: Long,
    val task: String,
    val completeness: String,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val featuresBase64: String,
    val availabilityMaskBase64: String?,
    val targetLabel: String,
    val feedbackSource: String,
    val sampleWeight: Float,
    val deliveryLane: String?,
    val domainId: String?,
    val opportunityGroupId: String?,
    val candidateOrdinal: Int?,
    val journeyLengthBucket: Int?,
    val naturalHoldoutEligible: Boolean,
    val occurredEpochDay: Long,
    val historical: Boolean
)

data class BootstrapTrainingBatchRequest(
    val schemaVersion: Int = BOOTSTRAP_PROTOCOL_VERSION,
    val batchId: String,
    val participantId: String,
    val consentLifecycleId: String,
    val consentSchemaVersion: Int,
    val revocationCapabilityHash: String,
    val appVersionCode: Int,
    val examples: List<BootstrapTrainingExamplePayload>
)

data class BootstrapTrainingCredentialRequest(
    val schemaVersion: Int = BOOTSTRAP_PROTOCOL_VERSION,
    val batchId: String,
    val bodySha256Hex: String,
    val appVersionCode: Int
)

data class BootstrapTrainingCredentialResponse(
    val credential: String,
    val expiresAtEpochMs: Long
)

data class BootstrapTrainingDeletionRequest(
    val schemaVersion: Int = BOOTSTRAP_PROTOCOL_VERSION,
    val deletionId: String,
    val participantId: String,
    val consentLifecycleId: String,
    val revocationCapability: String
)

internal fun BootstrapTrainingExampleEntity.toPayload() = BootstrapTrainingExamplePayload(
    exampleId = exampleId,
    sequenceNo = sequenceNo,
    task = task,
    completeness = completeness,
    featureSchemaVersion = featureSchemaVersion,
    outputSchemaVersion = outputSchemaVersion,
    actionCatalogVersion = actionCatalogVersion,
    featuresBase64 = Base64.getEncoder().encodeToString(features),
    availabilityMaskBase64 = availabilityMask?.let { Base64.getEncoder().encodeToString(it) },
    targetLabel = targetLabel,
    feedbackSource = feedbackSource,
    sampleWeight = sampleWeight,
    deliveryLane = deliveryLane,
    domainId = domainId,
    opportunityGroupId = opportunityGroupId,
    candidateOrdinal = candidateOrdinal,
    journeyLengthBucket = journeyLengthBucket,
    naturalHoldoutEligible = naturalHoldoutEligible,
    occurredEpochDay = occurredEpochDay,
    historical = historical
)

object BootstrapTrainingPayloadValidator {
    fun requireValid(request: BootstrapTrainingBatchRequest) {
        require(request.schemaVersion == BOOTSTRAP_PROTOCOL_VERSION)
        requireUuid(request.batchId)
        requireUuid(request.participantId)
        requireUuid(request.consentLifecycleId)
        require(request.consentSchemaVersion == BOOTSTRAP_CONSENT_SCHEMA_VERSION)
        require(LOWER_SHA256.matches(request.revocationCapabilityHash))
        require(request.appVersionCode > 0)
        require(request.examples.size in 1..MAX_EXAMPLES_PER_BATCH)
        require(request.examples.map { it.exampleId }.distinct().size == request.examples.size)
        require(request.examples.map { it.sequenceNo }.distinct().size == request.examples.size)
        require(request.examples.map { it.task }.distinct().size == 1)
        require(request.examples.count { it.sampleWeight < 1f } <= request.examples.size / 4)
        request.examples.forEach(::requireValidExample)
        val natural = request.examples.filter { it.sampleWeight == 1f }
        require(natural.isNotEmpty())
        val task = BootstrapTrainingTask.valueOf(request.examples.first().task)
        if (task == BootstrapTrainingTask.NEXT_ACTION || task == BootstrapTrainingTask.JOURNEY_GOAL) {
            require(request.examples.count { it.targetLabel == "NONE" } <= request.examples.size / 2)
        }
        if (task == BootstrapTrainingTask.PRESET_RANKING) {
            request.examples.groupBy { it.opportunityGroupId }.values.forEach { group ->
                require(group.map { it.candidateOrdinal }.distinct().size == group.size)
                if (group.all { it.sampleWeight == 1f }) {
                    require(group.count { it.targetLabel == "1" } <= 1)
                }
            }
        }
    }

    fun requireValidExample(value: BootstrapTrainingExamplePayload) {
        requireUuid(value.exampleId)
        require(value.sequenceNo > 0)
        require(value.task in BootstrapTrainingTask.entries.map(Enum<*>::name))
        require(value.completeness in BootstrapExampleCompleteness.entries.map(Enum<*>::name))
        require(value.featureSchemaVersion > 0 && value.outputSchemaVersion > 0)
        require(value.actionCatalogVersion > 0)
        require(value.sampleWeight.isFinite() && value.sampleWeight in 0.1f..1f)
        require(value.occurredEpochDay in 17_000L..100_000L)
        val expectedWeight = FEEDBACK_WEIGHTS[value.feedbackSource]
        require(expectedWeight != null && kotlin.math.abs(value.sampleWeight - expectedWeight) <= 0.000_001f)
        if (value.sampleWeight < 1f) require(!value.naturalHoldoutEligible)
        val features = BinaryCodec.floats(Base64.getDecoder().decode(value.featuresBase64))
        require(features.all(Float::isFinite))
        when (BootstrapTrainingTask.valueOf(value.task)) {
            BootstrapTrainingTask.NEXT_ACTION -> {
                require(
                    Triple(value.featureSchemaVersion, value.outputSchemaVersion, value.actionCatalogVersion) ==
                        Triple(FeatureExtractor.FEATURE_SCHEMA_VERSION, AppActionCatalog.OUTPUT_SCHEMA_VERSION, AppActionCatalog.ACTION_CATALOG_VERSION)
                )
                require(features.size == FeatureExtractor.INPUT_DIMENSION)
                requireValidFeatureRanges(features, value.task)
                require(value.targetLabel in AppActionCatalog.outputIndex)
                if (value.completeness == BootstrapExampleCompleteness.COMPLETE.name) {
                    val mask = Base64.getDecoder().decode(requireNotNull(value.availabilityMaskBase64))
                    require(mask.size == AppActionCatalog.outputIds.size)
                    require(mask.all { it == 0.toByte() || it == 1.toByte() })
                    require(mask.any { it == 1.toByte() })
                    require(mask.getOrNull(AppActionCatalog.outputIndex.getValue(value.targetLabel)) == 1.toByte())
                } else {
                    require(value.availabilityMaskBase64 == null)
                }
                require(value.feedbackSource in NEXT_ACTION_FEEDBACK)
                require(value.deliveryLane == "ORDINARY_NEXT_ACTION" || value.deliveryLane == "ORDINARY_JOURNEY")
                require(
                    value.domainId == null && value.opportunityGroupId == null &&
                        value.candidateOrdinal == null && value.journeyLengthBucket == null
                )
            }
            BootstrapTrainingTask.JOURNEY_GOAL -> {
                require(
                    Triple(value.featureSchemaVersion, value.outputSchemaVersion, value.actionCatalogVersion) ==
                        Triple(FeatureExtractor.FEATURE_SCHEMA_VERSION, JourneyGoalCatalog.OUTPUT_SCHEMA_VERSION, AppActionCatalog.ACTION_CATALOG_VERSION)
                )
                require(features.size == FeatureExtractor.INPUT_DIMENSION)
                requireValidFeatureRanges(features, value.task)
                require(value.targetLabel in JourneyGoalCatalog.outputIndex)
                require(value.feedbackSource in JOURNEY_FEEDBACK && value.sampleWeight == 1f)
                require(requireNotNull(value.journeyLengthBucket) in 0..4)
                require(
                    value.availabilityMaskBase64 == null && value.deliveryLane == null &&
                        value.domainId == null && value.opportunityGroupId == null && value.candidateOrdinal == null
                )
            }
            BootstrapTrainingTask.PRESET_RANKING -> {
                require(
                    Triple(value.featureSchemaVersion, value.outputSchemaVersion, value.actionCatalogVersion) ==
                        Triple(1, 1, AppActionCatalog.ACTION_CATALOG_VERSION)
                )
                require(features.size == PresetModelStateStore.INPUT_SIZE)
                requireValidFeatureRanges(features, value.task)
                require(value.targetLabel == "0" || value.targetLabel == "1")
                require(value.feedbackSource in PRESET_FEEDBACK)
                require(value.domainId in PRESET_DOMAINS)
                require(value.opportunityGroupId?.matches(LOWER_SHA256) == true)
                require(requireNotNull(value.candidateOrdinal) in 0..31)
                require(
                    value.availabilityMaskBase64 == null && value.deliveryLane == null &&
                        value.journeyLengthBucket == null
                )
            }
        }
    }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value) }.isSuccess)
    }

    private fun requireValidFeatureRanges(features: FloatArray, task: String) {
        if (task == BootstrapTrainingTask.PRESET_RANKING.name) {
            require(features.all { it in -0.000_001f..1.000_001f })
            return
        }
        features.forEachIndexed { index, feature ->
            val valid = when (index) {
                in 0..3 -> feature in -1.000_001f..1.000_001f
                in 18..31 -> feature in -0.000_001f..3.000_001f
                else -> feature in -0.000_001f..1.000_001f
            }
            require(valid) { "feature $index is outside its schema range" }
        }
    }
}

const val BOOTSTRAP_PROTOCOL_VERSION = 1
const val BOOTSTRAP_CONSENT_SCHEMA_VERSION = 1
const val MAX_EXAMPLES_PER_BATCH = 256
internal val LOWER_SHA256 = Regex("^[0-9a-f]{64}$")
private val FEEDBACK_WEIGHTS = mapOf(
    "ORGANIC_ACTION" to 1f,
    JourneyTrainingLabelPolicy.INTERVENTION_FREE_TIMEOUT to 1f,
    JourneyTrainingLabelPolicy.INTERVENTION_FREE_MAX_STEPS to 1f,
    JourneyTrainingLabelPolicy.ORGANIC_JOURNEY to 1f,
    "NATURAL_COMMIT" to 1f,
    "SUGGESTION_ACCEPTED" to 0.25f,
    "ASSISTED_QUERY_CONFIRMED" to 0.20f,
    "ASSISTED_REPLACED" to 0.10f,
    "ASSISTED_REMOVED" to 0.10f
)
private val NEXT_ACTION_FEEDBACK = setOf("ORGANIC_ACTION", "INTERVENTION_FREE_TIMEOUT", "SUGGESTION_ACCEPTED")
private val JOURNEY_FEEDBACK = JourneyTrainingLabelPolicy.supportedSources
private val PRESET_FEEDBACK = setOf(
    "NATURAL_COMMIT", "ASSISTED_QUERY_CONFIRMED", "ASSISTED_REPLACED", "ASSISTED_REMOVED"
)
private val PRESET_DOMAINS = setOf("FREE_CLASSROOM", "GRADE", "LOST_FOUND", "ELECTRICITY")
