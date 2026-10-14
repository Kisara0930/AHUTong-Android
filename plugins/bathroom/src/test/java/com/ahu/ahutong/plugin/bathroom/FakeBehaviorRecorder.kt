package com.ahu.ahutong.plugin.bathroom

import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.context.ExamDistanceBucket
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.semantic.ContentStateBucket
import com.ahu.ahutong.personalization.semantic.ErrorTypeBucket
import com.ahu.ahutong.personalization.semantic.MutationId
import com.ahu.ahutong.personalization.semantic.ResultCountBucket
import com.ahu.ahutong.personalization.semantic.SemanticDomain

/**
 * [BehaviorRecorder] 的 fake：记下 feature 报告了什么，其余一律忽略。
 *
 * feature 单测关心的是"界面/ViewModel 有没有在正确的时机报告正确的动作"，
 * 端侧怎么训练、怎么推理不是这些用例的题目。
 */
class FakeBehaviorRecorder : BehaviorRecorder {

    val organicActions = mutableListOf<AppActionId>()

    override fun recordOrganicAction(action: AppActionId) {
        organicActions += action
    }

    override fun recordContentState(
        domain: SemanticDomain,
        contentState: ContentStateBucket,
        freshnessBucket: Int,
        resultCount: ResultCountBucket,
        errorType: ErrorTypeBucket
    ) = Unit

    override fun reportCommittedMutation(
        mutationId: MutationId,
        oldValue: Any?,
        newValue: Any?,
        coarseValueBucket: String?
    ) = Unit

    override fun recordExamDistance(bucket: ExamDistanceBucket) = Unit
}
