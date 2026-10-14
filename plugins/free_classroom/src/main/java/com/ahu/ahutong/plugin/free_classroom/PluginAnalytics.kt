package com.ahu.ahutong.plugin.free_classroom

import com.ahu.ahutong.personalization.preset.*
import com.ahu.ahutong.personalization.semantic.*

/** Optional assistant integration; disabling the assistant removes this service without breaking queries. */
object PluginAnalytics {
    suspend fun rankLocalPresets(domain: SemanticDomain) = OptionalPresets.rank(domain)
    suspend fun applyLocalPreset(candidate: PresetCandidate) = OptionalPresets.apply(candidate)
    suspend fun markPresetRecommendationExposed(candidate: PresetCandidate) = OptionalPresets.markExposed(candidate)
    fun expirePresetInteractionAsync(token: PresetInteractionToken?) = OptionalPresets.expire(token)
    suspend fun recordNaturalPresetSubmission(submission: PresetSubmission, interactionToken: PresetInteractionToken?, candidatesAtOpportunity: List<PresetCandidate>) =
        OptionalPresets.recordNaturalSubmission(submission, interactionToken, candidatesAtOpportunity)
    fun onContentStateChanged(domain: SemanticDomain, contentState: ContentStateBucket, freshnessBucket: Int,
        resultCount: ResultCountBucket, errorType: ErrorTypeBucket = ErrorTypeBucket.NONE) =
        OptionalBehavior.recordContentState(domain, contentState, freshnessBucket, resultCount, errorType)
}
