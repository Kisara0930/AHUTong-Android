package com.ahu.ahutong.personalization.context

import com.ahu.ahutong.personalization.action.ActionSource
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.semantic.ContentContext
import com.ahu.ahutong.personalization.semantic.ContentStateBucket
import com.ahu.ahutong.personalization.semantic.ErrorTypeBucket
import com.ahu.ahutong.personalization.semantic.ResultCountBucket
import com.ahu.ahutong.personalization.semantic.SemanticChangeKind
import com.ahu.ahutong.personalization.semantic.SemanticContext
import com.ahu.ahutong.personalization.semantic.SemanticDomain
import com.ahu.ahutong.personalization.semantic.SemanticEventFamily
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Stable on-disk representation for prediction context snapshots.
 *
 * This deliberately avoids Gson reflection over [ContextSnapshot]. R8 is free to optimize the
 * runtime model, while persisted action identifiers and enum tokens remain stable across builds.
 */
object ContextSnapshotCodec {
    internal const val SCHEMA_VERSION = 2

    fun encode(snapshot: ContextSnapshot): String = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("epochDay", snapshot.epochDay)
        addProperty("minuteOfDay", snapshot.minuteOfDay)
        addProperty("dayType", snapshot.dayType.toWireToken())
        addNullableProperty("route", snapshot.route)
        addNullableProperty("previousAction", snapshot.previousAction?.stableId)
        add("recentActions", snapshot.recentActions.toJsonArray { it.stableId })
        addProperty("balanceBucket", snapshot.balanceBucket.toWireToken())
        addProperty("balanceFresh", snapshot.balanceFresh)
        addProperty("examDistanceBucket", snapshot.examDistanceBucket.toWireToken())
        addProperty("sessionDurationBucket", snapshot.sessionDurationBucket)
        addNullableProperty("semesterWeek", snapshot.semesterWeek)
        addNullableProperty("foregroundGapBucket", snapshot.foregroundGapBucket)
        addProperty("sessionDepth", snapshot.sessionDepth)
        addNullableProperty("pageDwellBucket", snapshot.pageDwellBucket)
        add("recentActionSources", snapshot.recentActionSources.toJsonArray { it.toWireToken() })
        add("personalFamilyFrequencies", snapshot.personalFamilyFrequencies.toJsonNumberArray())
        add("personalFamilyRecencies", snapshot.personalFamilyRecencies.toJsonNumberArray())
        add("semanticContext", snapshot.semanticContext?.toJson() ?: JsonNull.INSTANCE)
        add("contentContext", snapshot.contentContext?.toJson() ?: JsonNull.INSTANCE)
        addProperty("candidateSetSize", snapshot.candidateSetSize)
        addProperty("journeyPosition", snapshot.journeyPosition)
    }.toString()

    fun decode(encoded: String): ContextSnapshot {
        val root = try {
            JsonParser.parseString(encoded).asJsonObject
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid context snapshot JSON", error)
        }
        return try {
            val schemaVersion = root.requiredInt("schemaVersion")
            require(schemaVersion in 1..SCHEMA_VERSION) {
                "Unsupported context snapshot schema $schemaVersion"
            }
            ContextSnapshot(
                epochDay = root.requiredLong("epochDay"),
                minuteOfDay = root.requiredInt("minuteOfDay"),
                dayType = dayTypeFromWireToken(root.requiredString("dayType")),
                route = root.optionalString("route"),
                previousAction = root.optionalString("previousAction")?.let(::requiredAction),
                recentActions = root.requiredArray("recentActions").mapIndexed { index, element ->
                    requiredAction(element.requiredString("recentActions[$index]"))
                },
                balanceBucket = balanceBucketFromWireToken(root.requiredString("balanceBucket")),
                balanceFresh = root.requiredBoolean("balanceFresh"),
                examDistanceBucket = examDistanceBucketFromWireToken(
                    root.requiredString("examDistanceBucket")
                ),
                sessionDurationBucket = root.requiredInt("sessionDurationBucket"),
                semesterWeek = root.optionalInt("semesterWeek"),
                foregroundGapBucket = root.optionalInt("foregroundGapBucket"),
                sessionDepth = root.requiredInt("sessionDepth"),
                pageDwellBucket = root.optionalInt("pageDwellBucket"),
                recentActionSources = root.requiredArray("recentActionSources").mapIndexed { index, element ->
                    actionSourceFromWireToken(element.requiredString("recentActionSources[$index]"))
                },
                personalFamilyFrequencies = root.requiredFloatList("personalFamilyFrequencies"),
                personalFamilyRecencies = root.requiredFloatList("personalFamilyRecencies"),
                semanticContext = if (schemaVersion >= 2) root.optionalObject("semanticContext")?.toSemanticContext() else null,
                contentContext = if (schemaVersion >= 2) root.optionalObject("contentContext")?.toContentContext() else null,
                candidateSetSize = if (schemaVersion >= 2) root.requiredInt("candidateSetSize") else 0,
                journeyPosition = if (schemaVersion >= 2) root.requiredInt("journeyPosition") else 0
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid context snapshot payload", error)
        }
    }

    private fun requiredAction(stableId: String): AppActionId =
        requireNotNull(AppActionId.fromStableId(stableId)) {
            "Unknown action id in context snapshot: $stableId"
        }

    private fun DayType.toWireToken(): String = when (this) {
        DayType.WEEKDAY -> "weekday"
        DayType.WEEKEND -> "weekend"
    }

    private fun dayTypeFromWireToken(value: String): DayType = when (value) {
        "weekday" -> DayType.WEEKDAY
        "weekend" -> DayType.WEEKEND
        else -> throw IllegalArgumentException("Unknown day type in context snapshot: $value")
    }

    private fun BalanceBucket.toWireToken(): String = when (this) {
        BalanceBucket.UNKNOWN -> "unknown"
        BalanceBucket.ZERO_TO_FIVE -> "zero_to_five"
        BalanceBucket.FIVE_TO_TEN -> "five_to_ten"
        BalanceBucket.TEN_TO_TWENTY -> "ten_to_twenty"
        BalanceBucket.TWENTY_TO_FIFTY -> "twenty_to_fifty"
        BalanceBucket.FIFTY_PLUS -> "fifty_plus"
    }

    private fun balanceBucketFromWireToken(value: String): BalanceBucket = when (value) {
        "unknown" -> BalanceBucket.UNKNOWN
        "zero_to_five" -> BalanceBucket.ZERO_TO_FIVE
        "five_to_ten" -> BalanceBucket.FIVE_TO_TEN
        "ten_to_twenty" -> BalanceBucket.TEN_TO_TWENTY
        "twenty_to_fifty" -> BalanceBucket.TWENTY_TO_FIFTY
        "fifty_plus" -> BalanceBucket.FIFTY_PLUS
        else -> throw IllegalArgumentException("Unknown balance bucket in context snapshot: $value")
    }

    private fun ExamDistanceBucket.toWireToken(): String = when (this) {
        ExamDistanceBucket.UNKNOWN -> "unknown"
        ExamDistanceBucket.NONE -> "none"
        ExamDistanceBucket.WITHIN_ONE_DAY -> "within_one_day"
        ExamDistanceBucket.WITHIN_THREE_DAYS -> "within_three_days"
        ExamDistanceBucket.WITHIN_SEVEN_DAYS -> "within_seven_days"
        ExamDistanceBucket.LATER -> "later"
    }

    private fun examDistanceBucketFromWireToken(value: String): ExamDistanceBucket = when (value) {
        "unknown" -> ExamDistanceBucket.UNKNOWN
        "none" -> ExamDistanceBucket.NONE
        "within_one_day" -> ExamDistanceBucket.WITHIN_ONE_DAY
        "within_three_days" -> ExamDistanceBucket.WITHIN_THREE_DAYS
        "within_seven_days" -> ExamDistanceBucket.WITHIN_SEVEN_DAYS
        "later" -> ExamDistanceBucket.LATER
        else -> throw IllegalArgumentException("Unknown exam distance bucket in context snapshot: $value")
    }

    private fun ActionSource.toWireToken(): String = when (this) {
        ActionSource.ORGANIC -> "organic"
        ActionSource.SUGGESTION -> "suggestion"
        ActionSource.DEEPLINK -> "deeplink"
        ActionSource.RESTORE -> "restore"
        ActionSource.USER_PREFERENCE -> "user_preference"
        ActionSource.SYSTEM -> "system"
        ActionSource.DEBUG -> "debug"
    }

    private fun actionSourceFromWireToken(value: String): ActionSource = when (value) {
        "organic" -> ActionSource.ORGANIC
        "suggestion" -> ActionSource.SUGGESTION
        "deeplink" -> ActionSource.DEEPLINK
        "restore" -> ActionSource.RESTORE
        "user_preference" -> ActionSource.USER_PREFERENCE
        "system" -> ActionSource.SYSTEM
        "debug" -> ActionSource.DEBUG
        else -> throw IllegalArgumentException("Unknown action source in context snapshot: $value")
    }

    private fun JsonObject.addNullableProperty(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonObject.addNullableProperty(name: String, value: Int?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private inline fun <T> List<T>.toJsonArray(transform: (T) -> String): JsonArray =
        JsonArray().also { array -> forEach { array.add(transform(it)) } }

    private fun List<Float>.toJsonNumberArray(): JsonArray = JsonArray().also { array ->
        forEach { value ->
            require(value.isFinite()) { "Context snapshot contains a non-finite signal" }
            array.add(value)
        }
    }

    private fun JsonObject.requiredElement(name: String): JsonElement =
        get(name)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("Missing context snapshot field: $name")

    private fun JsonObject.requiredString(name: String): String =
        requiredElement(name).requiredString(name)

    private fun JsonElement.requiredString(name: String): String {
        require(isJsonPrimitive && asJsonPrimitive.isString) {
            "Context snapshot field $name must be a string"
        }
        return asString
    }

    private fun JsonObject.requiredInt(name: String): Int = requiredElement(name).asInt

    private fun JsonObject.requiredLong(name: String): Long = requiredElement(name).asLong

    private fun JsonObject.requiredBoolean(name: String): Boolean = requiredElement(name).asBoolean

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val element = requiredElement(name)
        require(element.isJsonArray) { "Context snapshot field $name must be an array" }
        return element.asJsonArray
    }

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.requiredString(name)

    private fun JsonObject.optionalInt(name: String): Int? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asInt

    private fun JsonObject.optionalObject(name: String): JsonObject? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            require(element.isJsonObject) { "Context snapshot field $name must be an object" }
            element.asJsonObject
        }

    private fun JsonObject.requiredFloatList(name: String): List<Float> =
        requiredArray(name).mapIndexed { index, element ->
            val value = element.asFloat
            require(value.isFinite()) { "Context snapshot field $name[$index] must be finite" }
            value
        }

    private fun SemanticContext.toJson(): JsonObject = JsonObject().apply {
        addProperty("eventFamily", eventFamily.name)
        addProperty("domain", domain.name)
        addProperty("semanticId", semanticId)
        addProperty("changeKind", changeKind.name)
        addProperty("ageBucket", ageBucket)
        addProperty("changeSetSize", changeSetSize)
        addProperty("stable", stable)
        addProperty("affectedCandidateSetVersion", affectedCandidateSetVersion)
        addProperty("coarseValueBucket", coarseValueBucket)
    }

    private fun JsonObject.toSemanticContext(): SemanticContext {
        val semanticId = requiredString("semanticId")
        require(semanticId.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) { "Invalid semantic id" }
        return SemanticContext(
            eventFamily = enumValueOf(requiredString("eventFamily")),
            domain = enumValueOf(requiredString("domain")),
            semanticId = semanticId,
            changeKind = enumValueOf(requiredString("changeKind")),
            ageBucket = requiredInt("ageBucket"),
            changeSetSize = requiredInt("changeSetSize"),
            stable = requiredBoolean("stable"),
            affectedCandidateSetVersion = requiredInt("affectedCandidateSetVersion"),
            coarseValueBucket = optionalString("coarseValueBucket") ?: "UNKNOWN"
        )
    }

    private fun ContentContext.toJson(): JsonObject = JsonObject().apply {
        addProperty("domain", domain.name)
        addProperty("state", state.name)
        addProperty("freshnessBucket", freshnessBucket)
        addProperty("resultCount", resultCount.name)
        addProperty("errorType", errorType.name)
    }

    private fun JsonObject.toContentContext(): ContentContext = ContentContext(
        domain = enumValueOf(requiredString("domain")),
        state = enumValueOf(requiredString("state")),
        freshnessBucket = requiredInt("freshnessBucket"),
        resultCount = enumValueOf(requiredString("resultCount")),
        errorType = enumValueOf(requiredString("errorType"))
    )
}
