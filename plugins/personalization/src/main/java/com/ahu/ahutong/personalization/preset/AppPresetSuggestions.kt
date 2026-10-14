package com.ahu.ahutong.personalization.preset

import com.ahu.ahutong.personalization.runtime.BehaviorPredictionRuntime
import com.ahu.ahutong.personalization.semantic.SemanticDomain

/**
 * [PresetSuggestions] 的生产实现：把建议的排序与反馈交给端侧运行时。
 *
 * 适配器落在 :app，因为运行时自己要读设置、要碰端侧模型；词汇与端口在 :data:personalization，
 * 于是界面不必认识任何一件端侧实现。
 */
class AppPresetSuggestions constructor(
    private val runtime: BehaviorPredictionRuntime
) : PresetSuggestions {

    override suspend fun rank(domain: SemanticDomain): List<PresetCandidate> =
        runtime.rankLocalPresets(domain)

    override suspend fun markExposed(candidate: PresetCandidate): PresetInteractionToken? =
        runtime.markPresetRecommendationExposed(candidate)

    override suspend fun apply(candidate: PresetCandidate): AppliedPreset? =
        runtime.applyLocalPreset(candidate)

    override fun expire(token: PresetInteractionToken?) =
        runtime.expirePresetInteractionAsync(token)

    override suspend fun recordNaturalSubmission(
        submission: PresetSubmission,
        interactionToken: PresetInteractionToken?,
        candidatesAtOpportunity: List<PresetCandidate>
    ) {
        runtime.recordNaturalPresetSubmission(submission, interactionToken, candidatesAtOpportunity)
    }
}
