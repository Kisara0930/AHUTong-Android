package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.promotion.PromotionStage
import com.ahu.ahutong.personalization.promotion.PromotionStateMachine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotionStateMachineTest {
    @Test
    fun promotionMustWalkEveryTierButCanFailClosed() {
        assertTrue(PromotionStateMachine.allows(PromotionStage.SHADOW, 0f, PromotionStage.ELIGIBLE, 0f))
        assertTrue(PromotionStateMachine.allows(PromotionStage.ELIGIBLE, 0f, PromotionStage.MIXED, 0.10f))
        assertTrue(PromotionStateMachine.allows(PromotionStage.MIXED, 0.10f, PromotionStage.MIXED, 0.25f))
        assertTrue(PromotionStateMachine.allows(PromotionStage.MIXED, 0.50f, PromotionStage.PRIMARY, 1f))
        assertFalse(PromotionStateMachine.allows(PromotionStage.SHADOW, 0f, PromotionStage.PRIMARY, 1f))
        assertFalse(PromotionStateMachine.allows(PromotionStage.ELIGIBLE, 0f, PromotionStage.MIXED, 0.50f))
        assertTrue(PromotionStateMachine.allows(PromotionStage.PRIMARY, 1f, PromotionStage.SHADOW, 0f))
    }
}
