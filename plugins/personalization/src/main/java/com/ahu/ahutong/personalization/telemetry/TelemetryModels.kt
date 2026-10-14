package com.ahu.ahutong.personalization.telemetry

import com.ahu.ahutong.personalization.action.AppActionCatalog
import java.time.LocalDate
import java.util.UUID

data class ModelMetricSums(
    val top1Correct: Long,
    val top3Hit: Long,
    val reciprocalRankSum: Double,
    val brierSum: Double,
    val logLossSum: Double
)

data class ModelAggregate(
    val modelVersion: Int,
    val top1Correct: Long,
    val top3Hit: Long,
    val reciprocalRankSum: Double,
    val brierSum: Double,
    val logLossSum: Double
)

data class PairwiseAggregate(
    val tinyWins: Int,
    val statWins: Int,
    val ties: Int,
    val pairedSampleCount: Int
)

data class ActionMetricSums(
    val actionId: String,
    val eligibleSampleCount: Int,
    val pairedSampleCount: Int,
    val statistical: ModelMetricSums,
    val tinyMlp: ModelMetricSums,
    val pairwise: PairwiseAggregate
)

data class ModelQualityEvaluationReport(
    val reportId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val windowId: String,
    val revocationCapabilityHash: String,
    val windowStartDay: String,
    val windowEndDay: String,
    val statLearnedDays: Long?,
    val tinyLearnedDays: Long?,
    val eligibleSampleCount: Int,
    val organicNonNoneSampleCount: Int,
    val statistical: ModelAggregate,
    val tinyMlp: ModelAggregate,
    val pairwise: PairwiseAggregate,
    val appVersionCode: Int,
    val metricSchemaVersion: Int,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val trainingConfigVersion: Int,
    val perAction: List<ActionMetricSums>,
    val statInferenceNanosSum: Long,
    val tinyInferenceNanosSum: Long,
    val trainingNanosSum: Long,
    val modelSizeBytesMax: Long
)

data class ModelQualityBatchRequest(
    val schemaVersion: Int = 2,
    val batchId: String,
    val reports: List<ModelQualityEvaluationReport>
)

data class TelemetryCredentialRequest(
    val schemaVersion: Int = 2,
    val batchId: String,
    val bodySha256Hex: String,
    val appVersionCode: Int
)

data class TelemetryCredentialResponse(
    val credential: String,
    val expiresAtEpochMs: Long
)

data class TelemetryDeletionRequest(
    val schemaVersion: Int = 2,
    val deletionId: String,
    val telemetryId: String,
    val modelGenerationId: String,
    val revocationCapability: String
)

object TelemetryPayloadValidator {
    private const val LOG_LOSS_MAX = 16.118096

    fun requireValid(report: ModelQualityEvaluationReport) {
        requireUuid(report.reportId)
        requireUuid(report.telemetryId)
        requireUuid(report.modelGenerationId)
        requireUuid(report.windowId)
        require(LOWER_SHA256.matches(report.revocationCapabilityHash))
        require(report.eligibleSampleCount >= 64)
        require(report.organicNonNoneSampleCount in 0..report.eligibleSampleCount)
        require(report.pairwise.pairedSampleCount in 0..report.eligibleSampleCount)
        require(report.pairwise.tinyWins + report.pairwise.statWins + report.pairwise.ties == report.pairwise.pairedSampleCount)
        val startDay = runCatching { LocalDate.parse(report.windowStartDay) }.getOrNull()
        val endDay = runCatching { LocalDate.parse(report.windowEndDay) }.getOrNull()
        require(startDay != null && endDay != null && !startDay.isAfter(endDay))
        require(report.statLearnedDays == null || report.statLearnedDays >= 0)
        require(report.tinyLearnedDays == null || report.tinyLearnedDays >= 0)
        require(report.appVersionCode > 0)
        require(report.featureSchemaVersion > 0 && report.outputSchemaVersion > 0)
        require(report.actionCatalogVersion > 0 && report.trainingConfigVersion > 0 && report.metricSchemaVersion == 1)
        require(report.statistical.modelVersion > 0 && report.tinyMlp.modelVersion > 0)
        requireModel(report.statistical.toSums(), report.pairwise.pairedSampleCount)
        requireModel(report.tinyMlp.toSums(), report.pairwise.pairedSampleCount)
        require(report.perAction.map(ActionMetricSums::actionId).distinct().size == report.perAction.size)
        report.perAction.forEach { action ->
            require(action.actionId in AppActionCatalog.outputIndex)
            require(action.actionId != AppActionCatalog.OTHER_OUTPUT_ID && action.actionId != AppActionCatalog.NONE_OUTPUT_ID)
            require(action.eligibleSampleCount >= 30 && action.pairedSampleCount >= 30)
            require(action.eligibleSampleCount <= report.eligibleSampleCount)
            require(action.pairedSampleCount <= action.eligibleSampleCount)
            require(action.pairedSampleCount <= report.pairwise.pairedSampleCount)
            require(action.pairwise.pairedSampleCount == action.pairedSampleCount)
            require(action.pairwise.tinyWins + action.pairwise.statWins + action.pairwise.ties == action.pairedSampleCount)
            requireModel(action.statistical, action.pairedSampleCount)
            requireModel(action.tinyMlp, action.pairedSampleCount)
        }
        require(report.statInferenceNanosSum >= 0 && report.tinyInferenceNanosSum >= 0)
        require(report.trainingNanosSum >= 0 && report.modelSizeBytesMax in 0..(512L * 1024L))
    }

    private fun requireModel(value: ModelMetricSums, paired: Int) {
        require(value.top1Correct in 0..paired.toLong())
        require(value.top3Hit in value.top1Correct..paired.toLong())
        require(value.reciprocalRankSum.isFinite() && value.reciprocalRankSum in 0.0..paired.toDouble())
        require(value.brierSum.isFinite() && value.brierSum in 0.0..(paired * 2.0 + 1e-6))
        require(value.logLossSum.isFinite() && value.logLossSum in 0.0..(paired * LOG_LOSS_MAX + 1e-6))
    }

    private fun ModelAggregate.toSums() = ModelMetricSums(
        top1Correct, top3Hit, reciprocalRankSum, brierSum, logLossSum
    )

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false))
    }

    private val LOWER_SHA256 = Regex("[0-9a-f]{64}")
}
