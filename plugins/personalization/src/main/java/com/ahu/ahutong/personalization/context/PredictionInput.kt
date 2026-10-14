package com.ahu.ahutong.personalization.context

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.action.ActionFamily
import com.ahu.ahutong.personalization.action.ActionSource
import com.ahu.ahutong.personalization.semantic.ContentContext
import com.ahu.ahutong.personalization.semantic.SemanticContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

class ImmutableFloatVector(values: FloatArray) {
    private val data = values.copyOf()

    val size: Int get() = data.size

    operator fun get(index: Int): Float = data[index]

    fun copy(): FloatArray = data.copyOf()

    fun toBytes(): ByteArray = ByteBuffer.allocate(data.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { data.forEach(::putFloat) }
        .array()

    override fun equals(other: Any?): Boolean = other is ImmutableFloatVector && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
}

class ImmutableBooleanVector(values: BooleanArray) {
    private val data = values.copyOf()

    val size: Int get() = data.size
    operator fun get(index: Int): Boolean = data[index]
    fun copy(): BooleanArray = data.copyOf()
    fun toBytes(): ByteArray = ByteArray(data.size) { if (data[it]) 1 else 0 }
}

enum class DayType { WEEKDAY, WEEKEND }

enum class BalanceBucket { UNKNOWN, ZERO_TO_FIVE, FIVE_TO_TEN, TEN_TO_TWENTY, TWENTY_TO_FIFTY, FIFTY_PLUS }


data class ContextSnapshot(
    val epochDay: Long,
    val minuteOfDay: Int,
    val dayType: DayType,
    val route: String?,
    val previousAction: AppActionId?,
    val recentActions: List<AppActionId>,
    val balanceBucket: BalanceBucket,
    val balanceFresh: Boolean,
    val examDistanceBucket: ExamDistanceBucket,
    val sessionDurationBucket: Int,
    val semesterWeek: Int? = null,
    val foregroundGapBucket: Int? = null,
    val sessionDepth: Int = recentActions.size,
    val pageDwellBucket: Int? = null,
    val recentActionSources: List<ActionSource> = emptyList(),
    val personalFamilyFrequencies: List<Float> = emptyList(),
    val personalFamilyRecencies: List<Float> = emptyList(),
    val semanticContext: SemanticContext? = null,
    val contentContext: ContentContext? = null,
    val candidateSetSize: Int = 0,
    val journeyPosition: Int = 0
)

data class PredictionInput(
    val profileKey: String,
    val decisionId: String,
    val featureSchemaVersion: Int,
    val outputSchemaVersion: Int,
    val actionCatalogVersion: Int,
    val features: ImmutableFloatVector,
    val businessAvailability: ImmutableBooleanVector,
    val inputDigest: String,
    val snapshot: ContextSnapshot
)

object FeatureExtractor {
    const val FEATURE_SCHEMA_VERSION = 4
    const val INPUT_DIMENSION = 96
    const val LEGACY_V3_INPUT_DIMENSION = 64

    fun build(
        profileKey: String,
        decisionId: String,
        snapshot: ContextSnapshot,
        availability: BooleanArray = BooleanArray(AppActionCatalog.outputIds.size) { true }
    ): PredictionInput {
        require(availability.size == AppActionCatalog.outputIds.size)
        val safeSnapshot = snapshot.copy(
            recentActions = immutableCopy(snapshot.recentActions),
            recentActionSources = immutableCopy(snapshot.recentActionSources),
            personalFamilyFrequencies = immutableCopy(snapshot.personalFamilyFrequencies),
            personalFamilyRecencies = immutableCopy(snapshot.personalFamilyRecencies)
        )
        val features = FloatArray(INPUT_DIMENSION)

        // 0..9: time and calendar. Unknown semester data has a separate mask at index 61.
        val timeAngle = (safeSnapshot.minuteOfDay.coerceIn(0, 1439) / 1440.0) * Math.PI * 2.0
        val dayOfWeek = LocalDate.ofEpochDay(safeSnapshot.epochDay).dayOfWeek.value
        val weekAngle = ((dayOfWeek - 1) / 7.0) * Math.PI * 2.0
        features[0] = sin(timeAngle).toFloat()
        features[1] = cos(timeAngle).toFloat()
        features[2] = sin(weekAngle).toFloat()
        features[3] = cos(weekAngle).toFloat()
        features[4] = if (safeSnapshot.dayType == DayType.WEEKEND) 1f else 0f
        features[5] = safeSnapshot.semesterWeek?.coerceIn(1, 24)?.div(24f) ?: 0f
        features[6] = if (safeSnapshot.examDistanceBucket in EXAM_SEASON_BUCKETS) 1f else 0f
        features[7] = (safeSnapshot.minuteOfDay / 240).coerceIn(0, 5) / 5f
        features[8] = if ((safeSnapshot.semesterWeek ?: 0) % 2 == 0 && safeSnapshot.semesterWeek != null) 1f else 0f
        features[9] = 0f // Reserved for a versioned local holiday type.

        // 10..17: session position. Network, battery and Data Saver intentionally stay in policy only.
        features[10] = if (safeSnapshot.sessionDepth == 0) 1f else 0f
        features[11] = if (safeSnapshot.sessionDepth > 0) 1f else 0f
        features[12] = safeSnapshot.foregroundGapBucket?.coerceIn(0, 7)?.div(7f) ?: 0f
        features[13] = safeSnapshot.sessionDurationBucket.coerceIn(0, 7) / 7f
        features[14] = safeSnapshot.sessionDepth.coerceIn(0, 16) / 16f
        features[15] = safeSnapshot.pageDwellBucket?.coerceIn(0, 7)?.div(7f) ?: 0f
        features[16] = if (safeSnapshot.foregroundGapBucket != null) 1f else 0f
        features[17] = if (safeSnapshot.route != null) 1f else 0f

        // 18..33: recent action sequence, action family and source mask.
        safeSnapshot.recentActions.takeLast(8).reversed().forEachIndexed { index, action ->
            val amount = 1f / (index + 1f)
            hashedOneHot(features, 18, 8, action.stableId, amount)
            hashedOneHot(features, 26, 6, AppActionCatalog.spec(action).family.name, amount)
        }
        val alignedSources = safeSnapshot.recentActionSources.takeLast(safeSnapshot.recentActions.size)
        features[32] = if (alignedSources.isEmpty()) 0f else alignedSources.count { it == ActionSource.ORGANIC } / alignedSources.size.toFloat()
        features[33] = if (safeSnapshot.previousAction != null) 1f else 0f

        // 34..47: persisted organic frequency and recency summaries, hashed by stable family name.
        ActionFamily.entries.forEachIndexed { index, family ->
            val frequency = safeSnapshot.personalFamilyFrequencies.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f
            val recency = safeSnapshot.personalFamilyRecencies.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f
            hashedOneHot(features, 34, 7, family.name, frequency)
            hashedOneHot(features, 41, 7, family.name, recency)
        }

        // 48..57: coarse business context only; precise balances and identifiers never enter the vector.
        features[48] = safeSnapshot.balanceBucket.ordinal / (BalanceBucket.entries.size - 1f)
        features[49] = if (safeSnapshot.balanceFresh) 1f else 0f
        features[50] = safeSnapshot.examDistanceBucket.ordinal / (ExamDistanceBucket.entries.size - 1f)
        when (safeSnapshot.examDistanceBucket) {
            ExamDistanceBucket.WITHIN_ONE_DAY -> features[51] = 1f
            ExamDistanceBucket.WITHIN_THREE_DAYS -> features[52] = 1f
            ExamDistanceBucket.WITHIN_SEVEN_DAYS -> features[53] = 1f
            ExamDistanceBucket.LATER, ExamDistanceBucket.NONE -> features[54] = 1f
            ExamDistanceBucket.UNKNOWN -> Unit
        }
        features[55] = availability.count(Boolean::not).let { unavailable ->
            1f - unavailable / availability.size.toFloat()
        }
        features[56] = familyAvailability(availability, ActionFamily.PAYMENT_ENTRY)
        features[57] = familyAvailability(availability, ActionFamily.ACADEMIC)

        // 58..63: explicit unknown/stability masks.
        features[58] = if (safeSnapshot.balanceBucket == BalanceBucket.UNKNOWN) 1f else 0f
        features[59] = if (!safeSnapshot.balanceFresh) 1f else 0f
        features[60] = if (safeSnapshot.examDistanceBucket == ExamDistanceBucket.UNKNOWN) 1f else 0f
        features[61] = if (safeSnapshot.semesterWeek == null) 1f else 0f
        features[62] = if (safeSnapshot.route == null) 1f else 0f
        features[63] = if (safeSnapshot.recentActions.isEmpty()) 1f else 0f

        // 64..71: semantic domain and family. Stable hashing keeps the catalog extensible.
        safeSnapshot.semanticContext?.let { semantic ->
            hashedOneHot(features, 64, 4, semantic.domain.name, 1f)
            hashedOneHot(features, 68, 4, semantic.eventFamily.name, 1f)

            // 72..79: stable semantic/mutation identifier.
            hashedOneHot(features, 72, 8, semantic.semanticId, 1f)

            // 80..83: change direction.
            hashedOneHot(features, 80, 4, semantic.changeKind.name, 1f)

            // 84..87: recency, change-set size and stability.
            features[84] = semantic.ageBucket.coerceIn(0, 7) / 7f
            features[85] = semantic.changeSetSize.coerceIn(1, 8) / 8f
            features[86] = if (semantic.stable) 1f else 0f
            features[87] = 1f
        }

        // 88..91: content state, freshness, result count and coarse error type.
        safeSnapshot.contentContext?.let { content ->
            features[88] = content.state.ordinal / (com.ahu.ahutong.personalization.semantic.ContentStateBucket.entries.size - 1f)
            features[89] = content.freshnessBucket.coerceIn(0, 7) / 7f
            features[90] = content.resultCount.ordinal / (com.ahu.ahutong.personalization.semantic.ResultCountBucket.entries.size - 1f)
            features[91] = content.errorType.ordinal / (com.ahu.ahutong.personalization.semantic.ErrorTypeBucket.entries.size - 1f)
        }

        // 92..95: candidate scope, journey position, catalog version and unknown mask.
        features[92] = safeSnapshot.candidateSetSize.coerceIn(0, 16) / 16f
        features[93] = safeSnapshot.journeyPosition.coerceIn(0, 5) / 5f
        features[94] = safeSnapshot.semanticContext?.affectedCandidateSetVersion?.coerceIn(0, 16)?.div(16f) ?: 0f
        features[95] = when {
            safeSnapshot.semanticContext == null && safeSnapshot.contentContext == null -> 1f
            safeSnapshot.contentContext == null -> 0.5f
            else -> 0f
        }

        val immutableFeatures = ImmutableFloatVector(features)
        val immutableAvailability = ImmutableBooleanVector(availability)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            canonicalContextBytes(safeSnapshot) + immutableFeatures.toBytes() + immutableAvailability.toBytes() +
                ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(FEATURE_SCHEMA_VERSION)
                    .putInt(AppActionCatalog.OUTPUT_SCHEMA_VERSION)
                    .putInt(AppActionCatalog.ACTION_CATALOG_VERSION)
                    .array()
        ).joinToString("") { "%02x".format(it) }

        return PredictionInput(
            profileKey = profileKey,
            decisionId = decisionId,
            featureSchemaVersion = FEATURE_SCHEMA_VERSION,
            outputSchemaVersion = AppActionCatalog.OUTPUT_SCHEMA_VERSION,
            actionCatalogVersion = AppActionCatalog.ACTION_CATALOG_VERSION,
            features = immutableFeatures,
            businessAvailability = immutableAvailability,
            inputDigest = digest,
            snapshot = safeSnapshot
        )
    }

    private fun <T> immutableCopy(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private fun canonicalContextBytes(value: ContextSnapshot): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                fun writeNullableString(text: String?) {
                    output.writeBoolean(text != null)
                    text?.let(output::writeUTF)
                }
                fun writeNullableInt(number: Int?) {
                    output.writeBoolean(number != null)
                    number?.let(output::writeInt)
                }
                output.writeLong(value.epochDay)
                output.writeInt(value.minuteOfDay)
                output.writeUTF(value.dayType.name)
                writeNullableString(value.route)
                writeNullableString(value.previousAction?.stableId)
                output.writeInt(value.recentActions.size)
                value.recentActions.forEach { output.writeUTF(it.stableId) }
                output.writeUTF(value.balanceBucket.name)
                output.writeBoolean(value.balanceFresh)
                output.writeUTF(value.examDistanceBucket.name)
                output.writeInt(value.sessionDurationBucket)
                writeNullableInt(value.semesterWeek)
                writeNullableInt(value.foregroundGapBucket)
                output.writeInt(value.sessionDepth)
                writeNullableInt(value.pageDwellBucket)
                output.writeInt(value.recentActionSources.size)
                value.recentActionSources.forEach { output.writeUTF(it.name) }
                output.writeInt(value.personalFamilyFrequencies.size)
                value.personalFamilyFrequencies.forEach(output::writeFloat)
                output.writeInt(value.personalFamilyRecencies.size)
                value.personalFamilyRecencies.forEach(output::writeFloat)
                output.writeBoolean(value.semanticContext != null)
                value.semanticContext?.let { semantic ->
                    output.writeUTF(semantic.eventFamily.name)
                    output.writeUTF(semantic.domain.name)
                    output.writeUTF(semantic.semanticId)
                    output.writeUTF(semantic.changeKind.name)
                    output.writeInt(semantic.ageBucket)
                    output.writeInt(semantic.changeSetSize)
                    output.writeBoolean(semantic.stable)
                    output.writeInt(semantic.affectedCandidateSetVersion)
                }
                output.writeBoolean(value.contentContext != null)
                value.contentContext?.let { content ->
                    output.writeUTF(content.domain.name)
                    output.writeUTF(content.state.name)
                    output.writeInt(content.freshnessBucket)
                    output.writeUTF(content.resultCount.name)
                    output.writeUTF(content.errorType.name)
                }
                output.writeInt(value.candidateSetSize)
                output.writeInt(value.journeyPosition)
            }
            bytes.toByteArray()
        }

    private fun familyAvailability(availability: BooleanArray, family: ActionFamily): Float {
        val indices = AppActionCatalog.outputIds.mapIndexedNotNull { index, stableId ->
            val action = AppActionId.fromStableId(stableId) ?: return@mapIndexedNotNull null
            index.takeIf { AppActionCatalog.spec(action).family == family }
        }
        return if (indices.isEmpty()) 1f else indices.count { availability[it] } / indices.size.toFloat()
    }

    private fun hashedOneHot(
        target: FloatArray,
        offset: Int,
        buckets: Int,
        value: String,
        amount: Float
    ) {
        val hash = value.fold(0x811c9dc5.toInt()) { acc, char -> (acc xor char.code) * 16777619 }
        val index = (hash and Int.MAX_VALUE) % buckets
        target[offset + index] += amount
    }

    private val EXAM_SEASON_BUCKETS = setOf(
        ExamDistanceBucket.WITHIN_ONE_DAY,
        ExamDistanceBucket.WITHIN_THREE_DAYS,
        ExamDistanceBucket.WITHIN_SEVEN_DAYS
    )
}

object V3ToV4FeatureAdapter {
    fun adapt(values: FloatArray, schemaVersion: Int): FloatArray = when (schemaVersion) {
        FeatureExtractor.FEATURE_SCHEMA_VERSION -> values.also {
            require(it.size == FeatureExtractor.INPUT_DIMENSION)
        }
        3 -> FloatArray(FeatureExtractor.INPUT_DIMENSION).also { migrated ->
            require(values.size == FeatureExtractor.LEGACY_V3_INPUT_DIMENSION)
            values.copyInto(migrated, endIndex = FeatureExtractor.LEGACY_V3_INPUT_DIMENSION)
        }
        else -> throw IllegalArgumentException("Unsupported feature schema $schemaVersion")
    }
}
