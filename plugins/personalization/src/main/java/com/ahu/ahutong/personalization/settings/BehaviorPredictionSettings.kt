package com.ahu.ahutong.personalization.settings

import com.ahu.ahutong.personalization.bootstrap.BootstrapContributionStatus
import com.ahu.ahutong.personalization.runtime.BehaviorPredictionRuntime
import kotlinx.coroutines.flow.StateFlow

/**
 * [PersonalizationSettings] 的生产实现：把设置页的动作交给端侧运行时。
 *
 * 适配器落在 :app，因为运行时自己要读设置、要碰端侧模型；接口与词汇在
 * :data:personalization，于是设置 feature 不必认识任何一件端侧实现。
 */
class BehaviorPredictionSettings constructor(
    private val runtime: BehaviorPredictionRuntime
) : PersonalizationSettings {

    override val contributionStatus: StateFlow<BootstrapContributionStatus> =
        runtime.bootstrapContributionStatus

    override fun dismissSuggestion() = runtime.hideSuggestion()

    override suspend fun cancelPredictivePrefetch() = runtime.cancelPredictivePrefetch()

    override suspend fun clearLearningRecord() = runtime.clearLearningRecord()

    override suspend fun setBootstrapTrainingContribution(enabled: Boolean, includeHistorical: Boolean) {
        runtime.setBootstrapTrainingConsent(enabled, includeHistorical)
    }
}
