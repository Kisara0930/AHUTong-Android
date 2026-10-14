package com.ahu.ahutong.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.data.model.AcademicFeatureAccess
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.ui.plugin.AhupGate

internal object ShellLearningDockState {
    var showSchedule by mutableStateOf(true)
    fun toggle() { showSchedule = !showSchedule }
}

internal fun shellRouteOwner(route: String): String? = when (route.removePrefix("plugin/").substringBefore('/').substringBefore('?')) {
    "home", "settings", "widgets" -> null
    "login", "me", "accounts" -> "account"
    "grade", "exam" -> "grade_exam"
    "info" -> "schedule"
    "school_calendar", "phone_book", "identity_code", "calendar", "cainiao" -> "campus_guide"
    "payment_qr", "card_balance_deposit", "card_bill", "billing", "billing_stats", "card_recharge", "cmb_card_recharge" -> "campus_card"
    "bathroom_deposit" -> "bathroom"
    "electricity_pay", "electricity_alert_settings", "electricity_recent_rooms" -> "electricity"
    "xuexiaotong", "learning" -> "chaoxing"
    "preferences", "theme_lab" -> "appearance"
    "repository_downloads", "repository_settings", "repository_directory", "repository_item" -> "repository"
    "circle" -> "campus_circle"
    else -> route.removePrefix("plugin/").substringBefore('/').substringBefore('?')
}

internal fun shellRouteAvailable(route: String): Boolean {
    val owner = shellRouteOwner(route) ?: return true
    return AhupGate.isFeatureEnabled(AppEnvironmentHolder.context(), owner) &&
        AcademicFeatureAccess.allowsRoute(SessionStore.currentUser()?.academicAccountType, route)
}
