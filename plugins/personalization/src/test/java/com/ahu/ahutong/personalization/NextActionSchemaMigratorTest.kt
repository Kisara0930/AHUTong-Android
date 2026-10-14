package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.inference.AdamWState
import com.ahu.ahutong.personalization.inference.TinyMlpMath
import com.ahu.ahutong.personalization.inference.TinyMlpParameters
import com.ahu.ahutong.personalization.model.NextActionSchemaMigrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextActionSchemaMigratorTest {
    @Test
    fun migrationPreservesPredictionsAndPadsFirstLayerAndMoments() {
        val legacy = TinyMlpParameters.initialize(
            inputSize = FeatureExtractor.LEGACY_V3_INPUT_DIMENSION,
            outputSize = AppActionCatalog.outputIds.size,
            seed = 42
        )
        val legacyInput = FloatArray(64) { (it - 32) / 32f }
        val optimizer = AdamWState.create(legacy).let { initial ->
            AdamWState(
                initial.firstMoments.mapIndexed { index, values -> FloatArray(values.size) { index + 0.25f } },
                initial.secondMoments.mapIndexed { index, values -> FloatArray(values.size) { index + 0.5f } },
                17
            )
        }

        val migrated = NextActionSchemaMigrator.migrateParameters(legacy)
        val migratedOptimizer = NextActionSchemaMigrator.migrateOptimizer(optimizer, legacy)
        val before = TinyMlpMath.forward(legacy, legacyInput).probabilities
        val after = TinyMlpMath.forward(migrated, legacyInput + FloatArray(32)).probabilities

        assertTrue(legacy.w1.contentEquals(migrated.w1.copyOfRange(0, legacy.w1.size)))
        assertTrue(migrated.w1.copyOfRange(legacy.w1.size, migrated.w1.size).all { it == 0f })
        assertTrue(before.indices.all { kotlin.math.abs(before[it] - after[it]) < 1e-6f })
        assertEquals(17, migratedOptimizer.step)
        assertTrue(optimizer.firstMoments[0].contentEquals(
            migratedOptimizer.firstMoments[0].copyOfRange(0, optimizer.firstMoments[0].size)
        ))
        assertTrue(migratedOptimizer.firstMoments[0]
            .copyOfRange(optimizer.firstMoments[0].size, migratedOptimizer.firstMoments[0].size)
            .all { it == 0f })
        assertTrue(migratedOptimizer.secondMoments[0]
            .copyOfRange(optimizer.secondMoments[0].size, migratedOptimizer.secondMoments[0].size)
            .all { it == 0f })
        assertTrue(legacy.b1.contentEquals(migrated.b1))
        assertTrue(legacy.w2.contentEquals(migrated.w2))
        assertTrue(legacy.w3.contentEquals(migrated.w3))
    }
}
