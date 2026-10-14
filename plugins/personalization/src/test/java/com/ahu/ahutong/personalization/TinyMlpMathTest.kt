package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.inference.AdamWState
import com.ahu.ahutong.personalization.inference.TinyMlpBackprop
import com.ahu.ahutong.personalization.inference.TinyMlpMath
import com.ahu.ahutong.personalization.inference.TinyMlpParameters
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TinyMlpMathTest {
    @Test
    fun softmaxIsFiniteAndNormalized() {
        val values = TinyMlpMath.softmax(floatArrayOf(1_000f, 999f, -1_000f))
        assertTrue(values.all { it.isFinite() && it in 0f..1f })
        assertTrue(abs(values.sum() - 1f) < 1e-5f)
    }

    @Test
    fun forwardIsDeterministic() {
        val parameters = TinyMlpParameters.initialize(inputSize = 4, hidden1Size = 3, hidden2Size = 2, outputSize = 3, seed = 7)
        val input = floatArrayOf(1f, 0f, 0.5f, -0.5f)
        val first = TinyMlpMath.forward(parameters, input).probabilities
        val second = TinyMlpMath.forward(parameters, input).probabilities
        assertTrue(first.contentEquals(second))
        assertEquals(3, first.size)
    }

    @Test
    fun trainingReducesLossOnSeparableSamples() {
        val parameters = TinyMlpParameters.initialize(inputSize = 2, hidden1Size = 8, hidden2Size = 4, outputSize = 2, seed = 42)
        val optimizer = AdamWState.create(parameters)
        val inputs = listOf(
            floatArrayOf(1f, 0f),
            floatArrayOf(0.9f, 0.1f),
            floatArrayOf(0f, 1f),
            floatArrayOf(0.1f, 0.9f)
        )
        val labels = intArrayOf(0, 0, 1, 1)
        val firstLoss = TinyMlpBackprop.trainBatch(parameters, optimizer, inputs, labels).averageLoss
        var latestLoss = firstLoss
        repeat(200) {
            latestLoss = TinyMlpBackprop.trainBatch(parameters, optimizer, inputs, labels).averageLoss
        }
        assertTrue(latestLoss < firstLoss * 0.5f, "first=$firstLoss latest=$latestLoss")
    }

    @Test
    fun backpropMatchesFiniteDifferenceForEveryWeightAndBiasLayer() {
        val parameters = TinyMlpParameters.initialize(
            inputSize = 3,
            hidden1Size = 4,
            hidden2Size = 3,
            outputSize = 3,
            seed = 19
        )
        val inputs = listOf(floatArrayOf(0.7f, -0.3f, 0.4f), floatArrayOf(-0.2f, 0.8f, 0.5f))
        val labels = intArrayOf(1, 2)
        val analytical = TinyMlpBackprop.gradients(parameters, inputs, labels)
        val epsilon = 1e-3f

        parameters.allArrays().indices.forEach { arrayIndex ->
            val plus = parameters.deepCopy()
            val minus = parameters.deepCopy()
            plus.allArrays()[arrayIndex][0] += epsilon
            minus.allArrays()[arrayIndex][0] -= epsilon
            val numerical = (crossEntropy(plus, inputs, labels) - crossEntropy(minus, inputs, labels)) /
                (2f * epsilon)
            assertTrue(
                abs(numerical - analytical.values[arrayIndex][0]) < 2e-2f,
                "array=$arrayIndex numerical=$numerical analytical=${analytical.values[arrayIndex][0]}"
            )
        }
    }

    @Test
    fun zeroWeightedRowsDoNotInfluenceLossOrGradients() {
        val parameters = TinyMlpParameters.initialize(
            inputSize = 2,
            hidden1Size = 4,
            hidden2Size = 3,
            outputSize = 2,
            seed = 23
        )
        val firstInput = floatArrayOf(1f, 0f)
        val ignoredInput = floatArrayOf(0f, 1f)
        val weighted = TinyMlpBackprop.gradients(
            parameters,
            listOf(firstInput, ignoredInput),
            intArrayOf(0, 1),
            floatArrayOf(1f, 0f)
        )
        val firstOnly = TinyMlpBackprop.gradients(parameters, listOf(firstInput), intArrayOf(0))

        assertEquals(firstOnly.averageLoss, weighted.averageLoss)
        firstOnly.values.zip(weighted.values).forEach { (expected, actual) ->
            assertTrue(expected.contentEquals(actual))
        }
    }

    private fun crossEntropy(
        parameters: TinyMlpParameters,
        inputs: List<FloatArray>,
        labels: IntArray
    ): Float = inputs.indices.sumOf { index ->
        -ln(TinyMlpMath.forward(parameters, inputs[index]).probabilities[labels[index]].coerceAtLeast(1e-7f).toDouble())
    }.div(inputs.size).toFloat()
}
