package com.ahu.ahutong.personalization.model

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.context.FeatureExtractor
import com.ahu.ahutong.personalization.inference.AdamWState
import com.ahu.ahutong.personalization.inference.TinyMlpParameters

/** Pure v3 -> v4 migration used by the atomic store and host-side regression tests. */
internal object NextActionSchemaMigrator {
    fun migrateParameters(parameters: TinyMlpParameters): TinyMlpParameters {
        require(parameters.inputSize == FeatureExtractor.LEGACY_V3_INPUT_DIMENSION)
        require(parameters.hidden1Size == 32 && parameters.hidden2Size == 16)
        require(parameters.outputSize == AppActionCatalog.outputIds.size)
        val expandedW1 = FloatArray(FeatureExtractor.INPUT_DIMENSION * parameters.hidden1Size)
        parameters.w1.copyInto(expandedW1)
        return parameters.copy(inputSize = FeatureExtractor.INPUT_DIMENSION, w1 = expandedW1)
    }

    fun migrateOptimizer(
        optimizer: AdamWState,
        legacyParameters: TinyMlpParameters
    ): AdamWState {
        require(legacyParameters.inputSize == FeatureExtractor.LEGACY_V3_INPUT_DIMENSION)
        fun expand(values: List<FloatArray>): List<FloatArray> = values.mapIndexed { index, array ->
            if (index == 0) {
                FloatArray(FeatureExtractor.INPUT_DIMENSION * legacyParameters.hidden1Size)
                    .also(array::copyInto)
            } else {
                array.copyOf()
            }
        }
        return AdamWState(
            firstMoments = expand(optimizer.firstMoments),
            secondMoments = expand(optimizer.secondMoments),
            step = optimizer.step
        )
    }
}
