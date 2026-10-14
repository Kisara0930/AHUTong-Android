package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.evaluation.PairedShadowModelEvaluator
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals

class ShadowMetricsTest {
    @Test
    fun contributionsUseFrozenPredictionAndTrueRank() {
        val metric = PairedShadowModelEvaluator.metric(floatArrayOf(0.6f, 0.3f, 0.1f), target = 1)
        assertEquals(0, metric.top1)
        assertEquals(1, metric.top3)
        assertEquals(0.5, metric.reciprocalRank, 1e-9)
        assertEquals(-ln(0.3), metric.logLoss, 1e-6)
        assertEquals(0.6 * 0.6 + (0.3 - 1) * (0.3 - 1) + 0.1 * 0.1, metric.brier, 1e-6)
    }
}
