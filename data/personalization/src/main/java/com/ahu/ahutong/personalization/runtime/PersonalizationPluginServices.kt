package com.ahu.ahutong.personalization.runtime

import com.ahu.ahutong.personalization.action.AppActionId
import kotlinx.coroutines.flow.StateFlow

/** Host dispatches only to installed, enabled feature providers; no business implementation is shared. */
interface PersonalizationFeatureAccess {
    fun isAvailable(action: AppActionId): Boolean
    suspend fun prefetch(action: AppActionId): Result<Unit>
    fun semesterWeek(epochDay: Long): Int?
    suspend fun preparePaymentQr(): Result<Unit>
    suspend fun clearPreparedPaymentQr()
    fun openPaymentQr(): Boolean
}

data class PersonalizationSuggestion(
    val executionId: String,
    val action: AppActionId,
    val title: String,
    val reason: String
)

/** Optional service: when absent the shell performs no collection or prediction. */
interface PersonalizationController {
    val suggestion: StateFlow<PersonalizationSuggestion?>
    fun setForeground(foreground: Boolean, interactive: Boolean = foreground)
    fun onRouteChanged(route: String?)
    fun setSensitiveUiVisible(visible: Boolean)
    suspend fun confirmSuggestionVisible(executionId: String): Boolean
    suspend fun acceptSuggestion(executionId: String): AppActionId?
    fun dismissSuggestion()
}
