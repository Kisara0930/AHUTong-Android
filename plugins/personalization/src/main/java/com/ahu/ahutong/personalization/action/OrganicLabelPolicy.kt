package com.ahu.ahutong.personalization.action

object OrganicLabelPolicy {
    fun isEligible(
        action: AppActionId,
        source: ActionSource,
        interventionState: String = "NONE",
        taintedChain: Boolean = false
    ): Boolean = source == ActionSource.ORGANIC &&
        AppActionCatalog.spec(action).labelEligible &&
        interventionState == "NONE" &&
        !taintedChain

    fun isTimeoutEligible(
        foregroundInteractive: Boolean,
        interventionState: String,
        preparationState: String,
        resolutionStatus: String
    ): Boolean = foregroundInteractive && interventionState == "NONE" &&
        preparationState == "PENDING" && resolutionStatus == "PENDING"
}
