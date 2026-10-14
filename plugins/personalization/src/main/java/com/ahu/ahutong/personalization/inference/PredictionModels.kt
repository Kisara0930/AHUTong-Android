package com.ahu.ahutong.personalization.inference

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.context.PredictionInput
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

data class NextActionProbabilityVector(
    val outputIds: List<String>,
    val probabilities: FloatArray,
    val inferenceNanos: Long = 0L,
    val modelVersion: Int
) {
    init {
        require(outputIds.size == probabilities.size)
        require(probabilities.all { it.isFinite() && it >= 0f && it <= 1f })
        require(kotlin.math.abs(probabilities.sum() - 1f) < 1e-3f)
    }

    fun probability(outputId: String): Float = probabilities.getOrElse(outputIds.indexOf(outputId)) { 0f }

    fun rankedIndices(): List<Int> = probabilities.indices.sortedWith(
        compareByDescending<Int> { probabilities[it] }.thenBy { it }
    )
}

interface NextActionPredictor {
    suspend fun predict(input: PredictionInput): NextActionProbabilityVector
    suspend fun reset(profileKey: String)
}

data class TinyMlpParameters(
    val inputSize: Int,
    val hidden1Size: Int,
    val hidden2Size: Int,
    val outputSize: Int,
    val w1: FloatArray,
    val b1: FloatArray,
    val w2: FloatArray,
    val b2: FloatArray,
    val w3: FloatArray,
    val b3: FloatArray,
    val trainingSteps: Long = 0L
) {
    init {
        require(w1.size == inputSize * hidden1Size)
        require(b1.size == hidden1Size)
        require(w2.size == hidden1Size * hidden2Size)
        require(b2.size == hidden2Size)
        require(w3.size == hidden2Size * outputSize)
        require(b3.size == outputSize)
        require(allArrays().all { array -> array.all(Float::isFinite) })
    }

    fun deepCopy(trainingSteps: Long = this.trainingSteps) = TinyMlpParameters(
        inputSize,
        hidden1Size,
        hidden2Size,
        outputSize,
        w1.copyOf(),
        b1.copyOf(),
        w2.copyOf(),
        b2.copyOf(),
        w3.copyOf(),
        b3.copyOf(),
        trainingSteps
    )

    fun allArrays(): List<FloatArray> = listOf(w1, b1, w2, b2, w3, b3)

    companion object {
        const val MODEL_VERSION = 1

        fun initialize(
            inputSize: Int = 96,
            hidden1Size: Int = 32,
            hidden2Size: Int = 16,
            outputSize: Int = AppActionCatalog.outputIds.size,
            seed: Long
        ): TinyMlpParameters {
            val random = Random(seed)
            fun he(size: Int, fanIn: Int): FloatArray {
                val scale = sqrt(2.0 / fanIn).toFloat()
                return FloatArray(size) { gaussian(random) * scale }
            }
            return TinyMlpParameters(
                inputSize,
                hidden1Size,
                hidden2Size,
                outputSize,
                he(inputSize * hidden1Size, inputSize),
                FloatArray(hidden1Size),
                he(hidden1Size * hidden2Size, hidden1Size),
                FloatArray(hidden2Size),
                he(hidden2Size * outputSize, hidden2Size),
                FloatArray(outputSize)
            )
        }

        private fun gaussian(random: Random): Float {
            val u1 = random.nextDouble().coerceAtLeast(1e-12)
            val u2 = random.nextDouble()
            return (sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
        }
    }
}

data class TinyForwardPass(
    val input: FloatArray,
    val hidden1PreActivation: FloatArray,
    val hidden1: FloatArray,
    val hidden2PreActivation: FloatArray,
    val hidden2: FloatArray,
    val logits: FloatArray,
    val probabilities: FloatArray
)

object TinyMlpMath {
    fun forward(parameters: TinyMlpParameters, input: FloatArray): TinyForwardPass {
        require(input.size == parameters.inputSize)
        require(input.all(Float::isFinite))
        val h1Pre = dense(input, parameters.w1, parameters.b1, parameters.hidden1Size)
        val h1 = FloatArray(h1Pre.size) { h1Pre[it].coerceAtLeast(0f) }
        val h2Pre = dense(h1, parameters.w2, parameters.b2, parameters.hidden2Size)
        val h2 = FloatArray(h2Pre.size) { h2Pre[it].coerceAtLeast(0f) }
        val logits = dense(h2, parameters.w3, parameters.b3, parameters.outputSize)
        val probabilities = softmax(logits)
        return TinyForwardPass(input.copyOf(), h1Pre, h1, h2Pre, h2, logits, probabilities)
    }

    fun softmax(logits: FloatArray): FloatArray {
        require(logits.isNotEmpty() && logits.all(Float::isFinite))
        val max = logits.max()
        val values = DoubleArray(logits.size) { exp((logits[it] - max).toDouble()) }
        val sum = values.sum()
        require(sum.isFinite() && sum > 0.0)
        return FloatArray(logits.size) { (values[it] / sum).toFloat() }
    }

    private fun dense(input: FloatArray, weights: FloatArray, bias: FloatArray, outputSize: Int): FloatArray {
        val result = bias.copyOf()
        for (inputIndex in input.indices) {
            val value = input[inputIndex]
            val row = inputIndex * outputSize
            for (outputIndex in 0 until outputSize) {
                result[outputIndex] += value * weights[row + outputIndex]
            }
        }
        require(result.all(Float::isFinite))
        return result
    }
}

data class AdamWState(
    val firstMoments: List<FloatArray>,
    val secondMoments: List<FloatArray>,
    var step: Long = 0L
) {
    companion object {
        fun create(parameters: TinyMlpParameters): AdamWState = AdamWState(
            parameters.allArrays().map { FloatArray(it.size) },
            parameters.allArrays().map { FloatArray(it.size) }
        )
    }
}

data class TinyTrainingResult(val averageLoss: Float, val gradientNorm: Float, val steps: Long)
internal data class TinyGradientResult(val averageLoss: Float, val values: List<FloatArray>)

object TinyMlpBackprop {
    fun trainBatch(
        parameters: TinyMlpParameters,
        optimizer: AdamWState,
        inputs: List<FloatArray>,
        labels: IntArray,
        sampleWeights: FloatArray? = null,
        learningRate: Float = 1e-3f,
        beta1: Float = 0.9f,
        beta2: Float = 0.999f,
        epsilon: Float = 1e-8f,
        weightDecay: Float = 1e-4f,
        gradientClipNorm: Float = 1f
    ): TinyTrainingResult {
        val gradientResult = gradients(parameters, inputs, labels, sampleWeights)
        val gradients = gradientResult.values

        val norm = sqrt(gradients.sumOf { gradient -> gradient.sumOf { (it * it).toDouble() } }).toFloat()
        val scale = if (norm > gradientClipNorm) gradientClipNorm / norm else 1f
        optimizer.step += 1
        val biasCorrection1 = 1.0 - Math.pow(beta1.toDouble(), optimizer.step.toDouble())
        val biasCorrection2 = 1.0 - Math.pow(beta2.toDouble(), optimizer.step.toDouble())
        val arrays = parameters.allArrays()
        arrays.indices.forEach { arrayIndex ->
            val values = arrays[arrayIndex]
            val gradient = gradients[arrayIndex]
            val first = optimizer.firstMoments[arrayIndex]
            val second = optimizer.secondMoments[arrayIndex]
            for (index in values.indices) {
                val g = gradient[index] * scale
                first[index] = beta1 * first[index] + (1f - beta1) * g
                second[index] = beta2 * second[index] + (1f - beta2) * g * g
                val mHat = first[index] / biasCorrection1.toFloat()
                val vHat = second[index] / biasCorrection2.toFloat()
                val decay = if (arrayIndex % 2 == 0) weightDecay * values[index] else 0f
                values[index] -= learningRate * (mHat / (sqrt(vHat) + epsilon) + decay)
            }
            require(values.all(Float::isFinite))
        }

        return TinyTrainingResult(gradientResult.averageLoss, norm, optimizer.step)
    }

    internal fun gradients(
        parameters: TinyMlpParameters,
        inputs: List<FloatArray>,
        labels: IntArray,
        sampleWeights: FloatArray? = null
    ): TinyGradientResult {
        require(inputs.isNotEmpty() && inputs.size == labels.size)
        require(labels.all { it in 0 until parameters.outputSize })
        require(sampleWeights == null || sampleWeights.size == inputs.size)
        val weights = sampleWeights ?: FloatArray(inputs.size) { 1f }
        require(weights.all { it.isFinite() && it >= 0f })
        val totalWeight = weights.sum()
        require(totalWeight > 0f)
        val gradients = parameters.allArrays().map { FloatArray(it.size) }
        var loss = 0.0

        inputs.indices.forEach { sampleIndex ->
            val pass = TinyMlpMath.forward(parameters, inputs[sampleIndex])
            val target = labels[sampleIndex]
            val sampleWeight = weights[sampleIndex]
            loss += -ln(pass.probabilities[target].coerceAtLeast(1e-7f).toDouble()) * sampleWeight
            val dLogits = pass.probabilities.copyOf().also { values ->
                values[target] -= 1f
                values.indices.forEach { values[it] *= sampleWeight }
            }

            outerAccumulate(gradients[4], pass.hidden2, dLogits, parameters.outputSize)
            addInPlace(gradients[5], dLogits)
            val dHidden2 = transposeMultiply(parameters.w3, dLogits, parameters.hidden2Size, parameters.outputSize)
            for (i in dHidden2.indices) if (pass.hidden2PreActivation[i] <= 0f) dHidden2[i] = 0f

            outerAccumulate(gradients[2], pass.hidden1, dHidden2, parameters.hidden2Size)
            addInPlace(gradients[3], dHidden2)
            val dHidden1 = transposeMultiply(parameters.w2, dHidden2, parameters.hidden1Size, parameters.hidden2Size)
            for (i in dHidden1.indices) if (pass.hidden1PreActivation[i] <= 0f) dHidden1[i] = 0f

            outerAccumulate(gradients[0], pass.input, dHidden1, parameters.hidden1Size)
            addInPlace(gradients[1], dHidden1)
        }

        val inverseWeight = 1f / totalWeight
        gradients.forEach { gradient -> for (i in gradient.indices) gradient[i] *= inverseWeight }
        return TinyGradientResult((loss / totalWeight).toFloat(), gradients)
    }

    private fun outerAccumulate(target: FloatArray, left: FloatArray, right: FloatArray, rightSize: Int) {
        for (leftIndex in left.indices) {
            val row = leftIndex * rightSize
            for (rightIndex in right.indices) target[row + rightIndex] += left[leftIndex] * right[rightIndex]
        }
    }

    private fun addInPlace(target: FloatArray, source: FloatArray) {
        for (index in source.indices) target[index] += source[index]
    }

    private fun transposeMultiply(
        weights: FloatArray,
        outputGradient: FloatArray,
        inputSize: Int,
        outputSize: Int
    ): FloatArray = FloatArray(inputSize) { inputIndex ->
        var sum = 0f
        val row = inputIndex * outputSize
        for (outputIndex in 0 until outputSize) sum += weights[row + outputIndex] * outputGradient[outputIndex]
        sum
    }
}
