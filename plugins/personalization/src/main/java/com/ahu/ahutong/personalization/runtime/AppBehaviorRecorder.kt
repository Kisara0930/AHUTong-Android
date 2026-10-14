package com.ahu.ahutong.personalization.runtime

import com.ahu.ahutong.personalization.action.ActionSource
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.semantic.ContentStateBucket
import com.ahu.ahutong.personalization.semantic.ErrorTypeBucket
import com.ahu.ahutong.personalization.semantic.MutationId
import com.ahu.ahutong.personalization.context.ExamDistanceBucket
import com.ahu.ahutong.personalization.semantic.ResultCountBucket
import com.ahu.ahutong.personalization.semantic.SemanticDomain

/**
 * 生产实现：把 [BehaviorRecorder] 接到端侧运行时上，行为与迁移前逐条对应——
 * 主动动作仍走 `recordActionIntentAsync(ORGANIC)`，内容状态仍走 `onContentStateChanged`。
 */
class AppBehaviorRecorder constructor(
    private val runtime: BehaviorPredictionRuntime
) : BehaviorRecorder {

    override fun recordOrganicAction(action: AppActionId) {
        runtime.recordActionIntentAsync(action, ActionSource.ORGANIC)
    }

    override fun recordContentState(
        domain: SemanticDomain,
        contentState: ContentStateBucket,
        freshnessBucket: Int,
        resultCount: ResultCountBucket,
        errorType: ErrorTypeBucket
    ) {
        runtime.onContentStateChanged(
            domain = domain,
            state = contentState,
            freshnessBucket = freshnessBucket,
            resultCount = resultCount,
            errorType = errorType
        )
    }

    /** 与迁移前一致：走异步版本，提交顺序由运行时的计数器保证。 */
    override fun reportCommittedMutation(
        mutationId: MutationId,
        oldValue: Any?,
        newValue: Any?,
        coarseValueBucket: String?
    ) {
        runtime.recordCommittedMutationAsync(
            mutationId = mutationId,
            oldValue = oldValue,
            newValue = newValue,
            coarseValueBucket = coarseValueBucket
        )
    }

    /** 与迁移前一致：交给运行时的业务上下文入口。 */
    override fun recordExamDistance(bucket: ExamDistanceBucket) {
        runtime.onBusinessContextChanged(newExamBucket = bucket)
    }
}
