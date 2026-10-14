package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.ActionSource
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.action.OrganicLabelPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganicLabelPolicyTest {
    @Test
    fun onlyIndependentOrganicActionCanBecomeLabel() {
        assertTrue(OrganicLabelPolicy.isEligible(AppActionId.VIEW_SCHEDULE, ActionSource.ORGANIC))
        listOf(
            ActionSource.SUGGESTION,
            ActionSource.DEEPLINK,
            ActionSource.RESTORE,
            ActionSource.USER_PREFERENCE,
            ActionSource.SYSTEM,
            ActionSource.DEBUG
        ).forEach { source ->
            assertFalse(OrganicLabelPolicy.isEligible(AppActionId.VIEW_SCHEDULE, source), source.name)
        }
        assertFalse(OrganicLabelPolicy.isEligible(AppActionId.VIEW_SCHEDULE, ActionSource.ORGANIC, "SUGGESTION_SHOWN"))
        assertFalse(OrganicLabelPolicy.isEligible(AppActionId.VIEW_SCHEDULE, ActionSource.ORGANIC, taintedChain = true))
        assertFalse(OrganicLabelPolicy.isEligible(AppActionId.LOGIN, ActionSource.ORGANIC))
    }

    @Test
    fun noneRequiresCleanForegroundPendingOpportunity() {
        assertTrue(OrganicLabelPolicy.isTimeoutEligible(true, "NONE", "PENDING", "PENDING"))
        assertFalse(OrganicLabelPolicy.isTimeoutEligible(false, "NONE", "PENDING", "PENDING"))
        assertFalse(OrganicLabelPolicy.isTimeoutEligible(true, "PREPARED_SUGGESTION", "PENDING", "PENDING"))
        assertFalse(OrganicLabelPolicy.isTimeoutEligible(true, "NONE", "PREPARING", "PENDING"))
    }
}
