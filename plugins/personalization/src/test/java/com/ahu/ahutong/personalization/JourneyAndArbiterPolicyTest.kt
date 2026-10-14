package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.action.SideEffect
import com.ahu.ahutong.personalization.journey.JourneyGoalCatalog
import com.ahu.ahutong.personalization.ui.ActionPredictionProposal
import com.ahu.ahutong.personalization.ui.ArbitratedPrediction
import com.ahu.ahutong.personalization.ui.PredictionArbiter
import com.ahu.ahutong.personalization.ui.PredictionTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JourneyAndArbiterPolicyTest {
    @Test
    fun shellAndTransactionActionsCanNeverBeJourneyTargets() {
        JourneyGoalCatalog.shellActions.forEach { assertFalse(JourneyGoalCatalog.isSafeTerminal(it)) }
        AppActionCatalog.specs.filter { it.sideEffect == SideEffect.TRANSACTION }.forEach {
            assertFalse(JourneyGoalCatalog.isSafeTerminal(it.id))
            assertFalse(it.id.stableId in JourneyGoalCatalog.outputIds)
        }
        assertTrue(JourneyGoalCatalog.isSafeTerminal(AppActionId.VIEW_SCHEDULE))
        assertTrue(AppActionId.OPEN_PAYMENT_QR.stableId in JourneyGoalCatalog.outputIds)
        assertEquals(JourneyGoalCatalog.outputIds.size, JourneyGoalCatalog.outputIds.distinct().size)
    }

    @Test
    fun onlyOneVisibleResultUsesPresetThenJourneyThenNextPriority() {
        val journey = proposal(PredictionTask.JOURNEY_GOAL, AppActionId.VIEW_SCHEDULE)
        val next = proposal(PredictionTask.NEXT_ACTION, AppActionId.OPEN_TOOLS)

        assertEquals(ArbitratedPrediction.InlinePreset(2), PredictionArbiter.choose(2, journey, next))
        assertEquals(ArbitratedPrediction.Action(journey), PredictionArbiter.choose(0, journey, next))
        assertEquals(ArbitratedPrediction.Action(next), PredictionArbiter.choose(0, null, next))
        assertEquals(ArbitratedPrediction.None, PredictionArbiter.choose(0, null, null))
    }

    private fun proposal(task: PredictionTask, action: AppActionId) =
        ActionPredictionProposal(task, action, 0.9f, task.name)
}
