package com.ahu.ahutong.personalization.ui

import com.ahu.ahutong.personalization.action.AppActionId

enum class PredictionTask { NEXT_ACTION, JOURNEY_GOAL, PRESET_RANKING }

data class ActionPredictionProposal(
    val task: PredictionTask,
    val action: AppActionId,
    val probability: Float,
    val decisionId: String
)

sealed interface ArbitratedPrediction {
    data class InlinePreset(val candidateCount: Int) : ArbitratedPrediction
    data class Action(val proposal: ActionPredictionProposal) : ArbitratedPrediction
    data object None : ArbitratedPrediction
}

object PredictionArbiter {
    fun choose(
        inlinePresetCandidateCount: Int,
        journey: ActionPredictionProposal?,
        nextAction: ActionPredictionProposal?
    ): ArbitratedPrediction = when {
        inlinePresetCandidateCount > 0 -> ArbitratedPrediction.InlinePreset(inlinePresetCandidateCount)
        journey != null -> ArbitratedPrediction.Action(journey)
        nextAction != null -> ArbitratedPrediction.Action(nextAction)
        else -> ArbitratedPrediction.None
    }
}
