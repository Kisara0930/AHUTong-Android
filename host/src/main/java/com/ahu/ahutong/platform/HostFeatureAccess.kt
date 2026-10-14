package com.ahu.ahutong.platform

import android.content.Context
import com.ahu.ahutong.core.plugin.PaymentQrService
import com.ahu.ahutong.core.plugin.PluginPrefetchHandler
import com.ahu.ahutong.core.plugin.PluginSemesterInfo
import com.ahu.ahutong.data.model.AcademicFeatureAccess
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.runtime.PersonalizationFeatureAccess
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.PluginServices
import com.ahu.ahutong.ui.plugin.RuntimePluginLoader
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class HostNavigationRequest(val sequence: Long, val pluginId: String, val route: String)

object HostNavigationRequests {
    private val serial = AtomicLong()
    private val mutable = MutableStateFlow<HostNavigationRequest?>(null)
    val pending = mutable.asStateFlow()
    fun open(pluginId: String, route: String = "") { mutable.value = HostNavigationRequest(serial.incrementAndGet(), pluginId, route) }
    fun consume(sequence: Long) { mutable.value?.takeIf { it.sequence == sequence }?.let { mutable.compareAndSet(it, null) } }
}

/** Compatibility vocabulary for existing prediction models. No feature implementation lives here. */
object LegacyActionDestinations {
    fun target(action: AppActionId): Pair<String, String>? = when (action) {
        AppActionId.VIEW_SCHEDULE, AppActionId.MANUAL_REFRESH_SCHEDULE, AppActionId.OPEN_COURSE_DETAIL -> "schedule" to "schedule"
        AppActionId.VIEW_GRADES, AppActionId.MANUAL_REFRESH_GRADE, AppActionId.RETRY_GRADE -> "grade_exam" to "grade"
        AppActionId.VIEW_EXAM_ROOM, AppActionId.MANUAL_REFRESH_EXAM, AppActionId.RETRY_EXAM -> "grade_exam" to "exam"
        AppActionId.OPEN_EVALUATION -> "evaluation" to "evaluation"
        AppActionId.FIND_FREE_CLASSROOM -> "free_classroom" to "free_classroom"
        AppActionId.VIEW_SCHOOL_CALENDAR -> "campus_guide" to "school_calendar"
        AppActionId.OPEN_PHONE_BOOK -> "campus_guide" to "phone_book"
        AppActionId.OPEN_LOST_FOUND -> "lost_found" to "lost_found"
        AppActionId.VIEW_WEATHER -> "weather" to "weather"
        AppActionId.OPEN_PAYMENT_QR, AppActionId.REFRESH_PAYMENT_QR -> "campus_card" to "payment_qr"
        AppActionId.OPEN_CARD_RECHARGE, AppActionId.SUBMIT_CARD_RECHARGE -> "campus_card" to "card_balance_deposit"
        AppActionId.OPEN_CMB_CARD_RECHARGE, AppActionId.SUBMIT_CMB_CARD_RECHARGE -> "campus_card" to "card_balance_deposit"
        AppActionId.OPEN_BATHROOM_DEPOSIT, AppActionId.CONFIRM_BATHROOM_PAYMENT -> "bathroom" to "bathroom_deposit"
        AppActionId.OPEN_ELECTRICITY_PAYMENT, AppActionId.CONFIRM_ELECTRICITY_PAYMENT -> "electricity" to "electricity_pay"
        AppActionId.OPEN_NETWORK_RECHARGE, AppActionId.SUBMIT_NETWORK_RECHARGE -> "network_recharge" to "network_recharge"
        AppActionId.OPEN_REPOSITORY, AppActionId.OPEN_REPOSITORY_DIRECTORY, AppActionId.OPEN_REPOSITORY_ITEM,
        AppActionId.DOWNLOAD_REPOSITORY_ITEM, AppActionId.MANUAL_REFRESH_REPOSITORY, AppActionId.RETRY_REPOSITORY -> "repository" to "repository"
        AppActionId.OPEN_REPOSITORY_DOWNLOADS -> "repository" to "repository_downloads"
        AppActionId.OPEN_REPOSITORY_SETTINGS -> "repository" to "repository_settings"
        AppActionId.OPEN_INFO -> "schedule" to "info"
        AppActionId.LOGIN -> "account" to "login"
        AppActionId.OPEN_PREFERENCES -> "appearance" to "preferences"
        else -> null
    }
}

class HostFeatureAccess(private val context: Context) : PersonalizationFeatureAccess {
    override fun isAvailable(action: AppActionId): Boolean {
        if (!AhupGate.isBootstrapValidated(context) || !AhupGate.isInitialized(context)) return false
        val target = LegacyActionDestinations.target(action) ?: return action in setOf(
            AppActionId.OPEN_HOME, AppActionId.OPEN_TOOLS, AppActionId.OPEN_SETTINGS, AppActionId.EDIT_HOME)
        return AhupGate.isFeatureEnabled(context, target.first) &&
            AcademicFeatureAccess.allowsRoute(SessionStore.currentUser()?.academicAccountType, target.second)
    }

    override suspend fun prefetch(action: AppActionId): Result<Unit> = withContext(Dispatchers.IO) {
        if (!AhupGate.ensureBackgroundReady(context)) return@withContext Result.failure(IllegalStateException("应用初始化或存储校验未完成"))
        if (!isAvailable(action)) return@withContext Result.failure(IllegalStateException("功能未启用"))
        val target = LegacyActionDestinations.target(action)
            ?: return@withContext Result.failure(IllegalArgumentException("此动作不支持预取"))
        try {
            val handler = RuntimePluginLoader.ensureLoaded(context, target.first) as? PluginPrefetchHandler
                ?: return@withContext Result.failure(IllegalArgumentException("此组件未提供预取能力"))
            handler.prefetch(action.stableId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    override fun semesterWeek(epochDay: Long): Int? {
        if (!AhupGate.isBootstrapValidated(context) || !AhupGate.isFeatureEnabled(context, "schedule")) return null
        return runCatching { (RuntimePluginLoader.ensureLoaded(context, "schedule") as? PluginSemesterInfo)?.semesterWeek(epochDay) }.getOrNull()
    }

    private fun payment(): PaymentQrService? {
        if (!AhupGate.isBootstrapValidated(context) || !AhupGate.isFeatureEnabled(context, "campus_card")) return null
        RuntimePluginLoader.ensureLoaded(context, "campus_card")
        return PluginServices.resolve(PaymentQrService::class.java)
    }
    override suspend fun preparePaymentQr(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!AhupGate.ensureBackgroundReady(context)) return@withContext Result.failure(IllegalStateException("应用初始化或存储校验未完成"))
        try { payment()?.prefetch() ?: Result.failure(IllegalStateException("校园卡未启用")) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    override suspend fun clearPreparedPaymentQr() { PluginServices.resolve(PaymentQrService::class.java)?.clearSensitive() }
    override fun openPaymentQr(): Boolean = runCatching {
        val service = payment() ?: return@runCatching false
        service.requestOpen()
        HostNavigationRequests.open("campus_card", "payment_qr")
        true
    }.getOrDefault(false)
}
