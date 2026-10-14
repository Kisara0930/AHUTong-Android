package com.ahu.ahutong.personalization

import com.ahu.ahutong.personalization.action.ActionSource
import com.ahu.ahutong.personalization.action.AppActionCatalog
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.semantic.CommittedMutation
import com.ahu.ahutong.personalization.semantic.AffectedActionCatalog
import com.ahu.ahutong.personalization.semantic.MutationId
import com.ahu.ahutong.personalization.semantic.SemanticChangeKind
import com.ahu.ahutong.personalization.semantic.SemanticEventRecorder
import com.ahu.ahutong.personalization.semantic.ProductCandidateResolver
import com.ahu.ahutong.personalization.semantic.ProductCandidateScope
import com.ahu.ahutong.personalization.semantic.SemanticContext
import com.ahu.ahutong.personalization.semantic.SemanticDomain
import com.ahu.ahutong.personalization.semantic.SemanticEventFamily
import com.ahu.ahutong.personalization.semantic.ContentContext
import com.ahu.ahutong.personalization.semantic.ContentStateBucket
import com.ahu.ahutong.personalization.semantic.ResultCountBucket
import com.ahu.ahutong.personalization.semantic.ErrorTypeBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticEventRecorderTest {
    private val recorder = SemanticEventRecorder()

    @Test
    fun unchangedValuesDoNotCreateCommittedEvents() {
        assertNull(recorder.normalize(mutation(true, true)))
        assertNull(recorder.normalize(mutation("same", "same")))
    }

    @Test
    fun stableIdsAndCoarseDirectionsReplaceRawValues() {
        val event = requireNotNull(recorder.normalize(mutation(false, true)))

        assertEquals("HOME_DEFAULT_QR_CHANGED", event.semanticId)
        assertEquals(SemanticChangeKind.ENABLED, event.changeKind)
        assertEquals("CHANGED", event.coarseValueBucket)
        assertEquals(setOf(AppActionId.OPEN_HOME), event.affectedActionIds)
        assertTrue(event.source == ActionSource.ORGANIC && !event.tainted)
        assertFalse(event.toString().contains("oldValue="))
        assertFalse(event.toString().contains("newValue="))
    }

    @Test
    fun directedSourcesAreTaintedAtTheCatalogBoundary() {
        val event = requireNotNull(recorder.normalize(mutation(false, true, ActionSource.SUGGESTION)))
        assertTrue(event.tainted)
    }

    @Test
    fun cmbRechargePreferenceTargetsOnlyThePersistedChoice() {
        val enabled = requireNotNull(
            recorder.normalize(
                mutation(false, true).copy(
                    mutationId = MutationId.CMB_RECHARGE_PREFERENCE_CHANGED,
                    coarseValueBucket = "ENABLED"
                )
            )
        )
        val disabled = requireNotNull(
            recorder.normalize(
                mutation(true, false).copy(
                    mutationId = MutationId.CMB_RECHARGE_PREFERENCE_CHANGED,
                    coarseValueBucket = "DISABLED"
                )
            )
        )

        assertEquals(SemanticChangeKind.ENABLED, enabled.changeKind)
        assertEquals(setOf(AppActionId.OPEN_CMB_CARD_RECHARGE), enabled.affectedActionIds)
        assertEquals(SemanticChangeKind.DISABLED, disabled.changeKind)
        assertEquals(setOf(AppActionId.OPEN_CARD_RECHARGE), disabled.affectedActionIds)
        assertTrue(AppActionCatalog.spec(AppActionId.OPEN_CMB_CARD_RECHARGE).suggestible)
        assertFalse(AppActionCatalog.spec(AppActionId.SUBMIT_CMB_CARD_RECHARGE).suggestible)
    }

    @Test
    fun homeWidgetTypeScopesItsStableSemanticIdAndCandidates() {
        val event = requireNotNull(recorder.normalize(
            mutation(null, "grade").copy(
                mutationId = MutationId.HOME_WIDGET_ADDED,
                coarseValueBucket = "GRADE"
            )
        ))

        assertEquals("HOME_WIDGET_ADDED_GRADE", event.semanticId)
        assertEquals(setOf(AppActionId.VIEW_GRADES), event.affectedActionIds)
        assertEquals(event.affectedActionIds, AffectedActionCatalog.affectedActionsForSemanticId(event.semanticId))
    }

    @Test
    fun productCandidateResolverIsFailClosedAndContextAware() {
        assertEquals(ProductCandidateScope.Ordinary, ProductCandidateResolver.resolve(null, null, "home"))

        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_HOME)),
            ProductCandidateResolver.resolve(context(MutationId.HOME_DEFAULT_QR_CHANGED), null, "preferences")
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_CMB_CARD_RECHARGE)),
            ProductCandidateResolver.resolve(
                context(
                    MutationId.CMB_RECHARGE_PREFERENCE_CHANGED.name,
                    SemanticChangeKind.ENABLED
                ),
                null,
                "preferences"
            )
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_CARD_RECHARGE)),
            ProductCandidateResolver.resolve(
                context(
                    MutationId.CMB_RECHARGE_PREFERENCE_CHANGED.name,
                    SemanticChangeKind.DISABLED
                ),
                null,
                "preferences"
            )
        )
        assertFalse(
            AppActionId.OPEN_PAYMENT_QR in
                (ProductCandidateResolver.resolve(context(MutationId.HOME_DEFAULT_QR_CHANGED), null, "preferences") as ProductCandidateScope.Targeted).actions
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_HOME)),
            ProductCandidateResolver.resolve(context(MutationId.WEATHER_HOME_CONFIG_CHANGED), null, "weather")
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.VIEW_SCHEDULE)),
            ProductCandidateResolver.resolve(context(MutationId.SCHEDULE_OVERVIEW_CHANGED), null, "preferences")
        )
        assertEquals(
            ProductCandidateScope.Suppress,
            ProductCandidateResolver.resolve(context(MutationId.SCHEDULE_OVERVIEW_CHANGED), null, "schedule")
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.VIEW_GRADES)),
            ProductCandidateResolver.resolve(context("HOME_WIDGET_ADDED_GRADE"), null, "home")
        )
        assertEquals(
            ProductCandidateScope.Suppress,
            ProductCandidateResolver.resolve(context("HOME_WIDGET_REMOVED_GRADE"), null, "home")
        )
        assertEquals(
            ProductCandidateScope.Suppress,
            ProductCandidateResolver.resolve(context("HOME_WIDGET_MOVED_GRADE"), null, "home")
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.VIEW_SCHEDULE)),
            ProductCandidateResolver.resolve(context(MutationId.COURSE_REMINDER_CHANGED), null, "preferences")
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_REPOSITORY)),
            ProductCandidateResolver.resolve(context(MutationId.REPOSITORY_ACCELERATION_CHANGED), null, "preferences")
        )
        assertEquals(
            ProductCandidateScope.Suppress,
            ProductCandidateResolver.resolve(context(MutationId.FREE_CLASSROOM_QUERY_COMMITTED), null, "free_classroom")
        )
        assertEquals(
            ProductCandidateScope.Suppress,
            ProductCandidateResolver.resolve(context("FUTURE_UNMAPPED_EVENT"), null, "preferences")
        )
    }

    @Test
    fun onlySupportedContentErrorsCreateTargetedRetryScope() {
        val error = ContentContext(
            SemanticDomain.GRADE,
            ContentStateBucket.ERROR,
            freshnessBucket = 7,
            resultCount = ResultCountBucket.ZERO,
            errorType = ErrorTypeBucket.NETWORK
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.RETRY_GRADE)),
            ProductCandidateResolver.resolve(null, error, "grade")
        )
        assertEquals(
            ProductCandidateScope.Suppress,
            ProductCandidateResolver.resolve(null, error.copy(state = ContentStateBucket.READY), "grade")
        )
    }

    @Test
    fun passiveWeatherContentCannotOverrideActiveHomeSettingScope() {
        val ready = ContentContext(
            SemanticDomain.WEATHER,
            ContentStateBucket.READY,
            freshnessBucket = 0,
            resultCount = ResultCountBucket.ONE_TO_FIVE,
            errorType = ErrorTypeBucket.NONE
        )
        val error = ready.copy(
            state = ContentStateBucket.ERROR,
            resultCount = ResultCountBucket.ZERO,
            errorType = ErrorTypeBucket.NETWORK
        )
        val setting = context(MutationId.WEATHER_HOME_CONFIG_CHANGED)

        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_HOME)),
            ProductCandidateResolver.resolve(setting, ready, "weather")
        )
        assertEquals(
            ProductCandidateScope.Targeted(setOf(AppActionId.OPEN_HOME)),
            ProductCandidateResolver.resolve(setting, error, "weather")
        )
    }

    private fun context(mutationId: MutationId) = context(mutationId.name)

    private fun context(
        semanticId: String,
        changeKind: SemanticChangeKind = SemanticChangeKind.REPLACED
    ) = SemanticContext(
        eventFamily = SemanticEventFamily.SETTING_CHANGED,
        domain = SemanticDomain.HOME,
        semanticId = semanticId,
        changeKind = changeKind,
        ageBucket = 0,
        changeSetSize = 1,
        stable = true
    )

    private fun mutation(old: Any?, new: Any?, source: ActionSource = ActionSource.ORGANIC) =
        CommittedMutation(
            mutationId = MutationId.HOME_DEFAULT_QR_CHANGED,
            oldValue = old,
            newValue = new,
            source = source,
            route = "preferences",
            committedAtEpochDay = 20_000,
            occurredAtElapsedMs = 5_000
        )
}
