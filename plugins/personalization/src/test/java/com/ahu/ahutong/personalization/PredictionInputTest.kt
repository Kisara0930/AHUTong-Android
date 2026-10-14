package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.context.BalanceBucket
import com.ahu.ahutong.personalization.context.ContextSnapshot
import com.ahu.ahutong.personalization.context.DayType
import com.ahu.ahutong.personalization.context.ExamDistanceBucket
import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.context.V3ToV4FeatureAdapter
import com.ahu.ahutong.personalization.semantic.SemanticChangeKind
import com.ahu.ahutong.personalization.semantic.SemanticContext
import com.ahu.ahutong.personalization.semantic.SemanticDomain
import com.ahu.ahutong.personalization.semantic.SemanticEventFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PredictionInputTest {
    private val snapshot = ContextSnapshot(
        epochDay = 20_000,
        minuteOfDay = 22 * 60,
        dayType = DayType.WEEKDAY,
        route = "home",
        previousAction = AppActionId.OPEN_HOME,
        recentActions = listOf(AppActionId.VIEW_SCHEDULE, AppActionId.OPEN_HOME),
        balanceBucket = BalanceBucket.TEN_TO_TWENTY,
        balanceFresh = true,
        examDistanceBucket = ExamDistanceBucket.WITHIN_THREE_DAYS,
        sessionDurationBucket = 2
    )

    @Test
    fun featureVectorIsFixedAndDefensive() {
        val input = FeatureExtractor.build("profile", "decision", snapshot)
        val first = input.features.copy()
        first[0] = 999f
        assertEquals(FeatureExtractor.INPUT_DIMENSION, input.features.size)
        assertNotEquals(999f, input.features[0])
    }

    @Test
    fun digestChangesWithContext() {
        val first = FeatureExtractor.build("profile", "decision-a", snapshot)
        val second = FeatureExtractor.build(
            "profile",
            "decision-b",
            snapshot.copy(minuteOfDay = snapshot.minuteOfDay + 60)
        )
        assertNotEquals(first.inputDigest, second.inputDigest)
        val differentRouteWithSameFeaturePresence = FeatureExtractor.build(
            "profile",
            "decision-c",
            snapshot.copy(route = "settings")
        )
        assertNotEquals(first.inputDigest, differentRouteWithSameFeaturePresence.inputDigest)
    }

    @Test
    fun missingBusinessValuesHaveExplicitMaskBits() {
        val first = FeatureExtractor.build("profile", "decision-a", snapshot)
        val second = FeatureExtractor.build(
            "profile",
            "decision-b",
            snapshot.copy(
                balanceBucket = BalanceBucket.UNKNOWN,
                balanceFresh = false,
                examDistanceBucket = ExamDistanceBucket.UNKNOWN,
                semesterWeek = null
            )
        )
        assertTrue(first.features[58] == 0f && second.features[58] == 1f)
        assertTrue(first.features[59] == 0f && second.features[59] == 1f)
        assertTrue(first.features[60] == 0f && second.features[60] == 1f)
        assertTrue(second.features[61] == 1f)
    }

    @Test
    fun semanticFeaturesOnlyOccupyTheAppendedSchemaRange() {
        val baseline = FeatureExtractor.build("profile", "base", snapshot)
        val semantic = FeatureExtractor.build(
            "profile",
            "semantic",
            snapshot.copy(
                semanticContext = SemanticContext(
                    SemanticEventFamily.SETTING_CHANGED,
                    SemanticDomain.HOME,
                    "HOME_DEFAULT_QR_CHANGED",
                    SemanticChangeKind.ENABLED,
                    ageBucket = 2,
                    changeSetSize = 3,
                    stable = true
                ),
                candidateSetSize = 2,
                journeyPosition = 1
            )
        )

        assertTrue(baseline.features.copy().copyOfRange(0, 64).contentEquals(
            semantic.features.copy().copyOfRange(0, 64)
        ))
        assertTrue(semantic.features.copy().copyOfRange(64, 96).any { it != 0f })
    }

    @Test
    fun legacySamplesKeepAllOldCoordinatesAndPadZeros() {
        val legacy = FloatArray(FeatureExtractor.LEGACY_V3_INPUT_DIMENSION) { it / 64f }
        val migrated = V3ToV4FeatureAdapter.adapt(legacy, 3)

        assertTrue(legacy.contentEquals(migrated.copyOfRange(0, 64)))
        assertTrue(migrated.copyOfRange(64, 96).all { it == 0f })
    }
}
