package com.ahu.ahutong.testing

import com.ahu.ahutong.core.storage.SettingsStore
import com.ahu.ahutong.core.storage.StartupThemePreferences
import com.ahu.ahutong.core.storage.CampusCardSessionControl
import com.ahu.ahutong.data.model.AppThemeMode
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.data.model.CampusCardProvider
import com.ahu.ahutong.data.session.CredentialVault
import com.ahu.ahutong.personalization.bootstrap.BootstrapContributionStatus
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.semantic.ContentStateBucket
import com.ahu.ahutong.personalization.semantic.ErrorTypeBucket
import com.ahu.ahutong.personalization.semantic.MutationId
import com.ahu.ahutong.personalization.semantic.ResultCountBucket
import com.ahu.ahutong.personalization.semantic.SemanticDomain
import com.ahu.ahutong.personalization.context.ExamDistanceBucket
import com.ahu.ahutong.personalization.settings.PersonalizationSettings
import com.ahu.ahutong.core.common.CourseReminderControl
import com.ahu.ahutong.ui.state.AppDataReset
import com.ahu.ahutong.ui.state.AppUpdateGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * 设置 feature 的 fake 适配器：内存里的一份设置 + 一份行为上报记录。
 *
 * 覆盖写 `MutableStateFlow` 而不是 `Flow`：`MutableStateFlow` 本身就是 `Flow`，
 * 于是测试既能读也能推着状态走，而 ViewModel 看到的仍然只是接口。
 */
class FakeSettingsStore : SettingsStore {

    override val appUiTheme = MutableStateFlow(AppUiTheme.RADIANT)
    override val themeColor = MutableStateFlow<String?>(null)
    override val themeMode = MutableStateFlow(AppThemeMode.FOLLOW_SYSTEM)
    override val showQRCode = MutableStateFlow(false)
    override val isShowAllCourse = MutableStateFlow(false)
    override val useBuiltInSecurePasswordKeyboard = MutableStateFlow(true)
    override val courseReminderEnabled = MutableStateFlow(false)
    override val courseReminderLiveCountdownEnabled = MutableStateFlow(false)
    override val repositoryAccelerationSource = MutableStateFlow("jsdelivr")
    override val campusCardProvider = MutableStateFlow(CampusCardProvider.AHU)
    override val campusCardCustomAccount = MutableStateFlow<String?>(null)
    override val personalizationEnabled = MutableStateFlow(true)
    override val predictivePrefetchEnabled = MutableStateFlow(false)
    override val wifiOnlyPrefetch = MutableStateFlow(false)
    override val behaviorRetentionDays = MutableStateFlow(30)
    override val modelQualityTelemetryOnboardingChoice = MutableStateFlow<Boolean?>(null)
    override val bootstrapTrainingOnboardingChoice = MutableStateFlow<Boolean?>(null)
    override val bootstrapTrainingIncludeHistorical = MutableStateFlow(false)

    var startupTheme: StartupThemePreferences? = null

    /** 置 true 后校园卡相关写入抛 IOException（模拟 DataStore 写失败）。 */
    var failCampusCardWrites = false

    var clearAllCount = 0
        private set

    val claimedProfiles = mutableListOf<String>()

    private val modelQualityTelemetryProfiles = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private val bootstrapTrainingProfiles = mutableMapOf<String, MutableStateFlow<Boolean>>()

    override fun getStartupThemePreferences(): StartupThemePreferences? = startupTheme

    override suspend fun rememberStartupThemePreferences(
        appUiTheme: AppUiTheme,
        themeColor: String?,
        themeMode: AppThemeMode
    ) {
        startupTheme = StartupThemePreferences(appUiTheme, themeColor, themeMode)
    }

    override suspend fun clearAll() {
        clearAllCount++
    }

    override suspend fun setAppUiTheme(value: AppUiTheme) { appUiTheme.value = value }

    override suspend fun setThemeColor(value: String?) { themeColor.value = value }

    override suspend fun setThemeMode(value: AppThemeMode) { themeMode.value = value }

    override suspend fun setShowQRCode(value: Boolean) { showQRCode.value = value }

    override suspend fun setIsShowAllCourse(value: Boolean) { isShowAllCourse.value = value }

    override suspend fun setUseBuiltInSecurePasswordKeyboard(value: Boolean) {
        useBuiltInSecurePasswordKeyboard.value = value
    }

    override suspend fun setCourseReminderEnabled(value: Boolean) {
        courseReminderEnabled.value = value
    }

    override suspend fun setCourseReminderLiveCountdownEnabled(value: Boolean) {
        courseReminderLiveCountdownEnabled.value = value
    }

    override suspend fun setRepositoryAccelerationSource(value: String) {
        repositoryAccelerationSource.value = value
    }

    override suspend fun setCampusCardProvider(value: CampusCardProvider) {
        if (failCampusCardWrites) throw IOException("datastore write failed")
        campusCardProvider.value = value
    }

    override suspend fun setCampusCardCustomAccount(value: String?) {
        if (failCampusCardWrites) throw IOException("datastore write failed")
        campusCardCustomAccount.value = value
    }

    override suspend fun setPersonalizationEnabled(value: Boolean) {
        personalizationEnabled.value = value
    }

    override suspend fun setPredictivePrefetchEnabled(value: Boolean) {
        predictivePrefetchEnabled.value = value
    }

    override suspend fun setWifiOnlyPrefetch(value: Boolean) { wifiOnlyPrefetch.value = value }

    override suspend fun setBehaviorRetentionDays(value: Int) { behaviorRetentionDays.value = value }

    override suspend fun setModelQualityTelemetryOnboardingChoice(value: Boolean) {
        modelQualityTelemetryOnboardingChoice.value = value
    }

    override fun modelQualityTelemetryEnabled(profileKey: String): Flow<Boolean> =
        modelQualityTelemetryProfiles.getOrPut(profileKey) { MutableStateFlow(false) }

    override suspend fun setModelQualityTelemetryEnabled(profileKey: String, value: Boolean) {
        modelQualityTelemetryEnabled(profileKey)
        modelQualityTelemetryProfiles.getValue(profileKey).value = value
    }

    override suspend fun setBootstrapTrainingOnboardingChoice(
        value: Boolean,
        includeHistorical: Boolean
    ) {
        bootstrapTrainingOnboardingChoice.value = value
        bootstrapTrainingIncludeHistorical.value = includeHistorical
    }

    override fun bootstrapTrainingEnabled(profileKey: String): Flow<Boolean> =
        bootstrapTrainingProfiles.getOrPut(profileKey) { MutableStateFlow(false) }

    override suspend fun setBootstrapTrainingEnabled(profileKey: String, enabled: Boolean) {
        bootstrapTrainingEnabled(profileKey)
        bootstrapTrainingProfiles.getValue(profileKey).value = enabled
    }

    override suspend fun claimBootstrapTrainingOnboardingForProfile(profileKey: String): Boolean {
        claimedProfiles += profileKey
        return true
    }
}

/** [BehaviorRecorder] 的 fake：把上报记下来，不训练也不推理。 */
class FakeBehaviorRecorder : BehaviorRecorder {

    data class Report(
        val mutationId: MutationId,
        val oldValue: Any?,
        val newValue: Any?,
        val coarseValueBucket: String?
    )

    val reports = mutableListOf<Report>()
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

    override fun recordExamDistance(bucket: ExamDistanceBucket) = Unit

    override fun reportCommittedMutation(
        mutationId: MutationId,
        oldValue: Any?,
        newValue: Any?,
        coarseValueBucket: String?
    ) {
        reports += Report(mutationId, oldValue, newValue, coarseValueBucket)
    }
}

/** [PersonalizationSettings] 的 fake：把上报记下来，不训练也不推理。 */
class FakePersonalizationSettings : PersonalizationSettings {

    val status = MutableStateFlow(BootstrapContributionStatus())

    override val contributionStatus: StateFlow<BootstrapContributionStatus> = status

    val contributions = mutableListOf<Pair<Boolean, Boolean>>()

    var dismissedCount = 0
        private set

    var cancelledPrefetchCount = 0
        private set

    var clearLearningCount = 0
        private set

    override fun dismissSuggestion() {
        dismissedCount++
    }

    override suspend fun cancelPredictivePrefetch() {
        cancelledPrefetchCount++
    }

    override suspend fun clearLearningRecord() {
        clearLearningCount++
    }

    override suspend fun setBootstrapTrainingContribution(
        enabled: Boolean,
        includeHistorical: Boolean
    ) {
        contributions += enabled to includeHistorical
    }
}

/** [CourseReminderControl] 的 fake：只记下命令，不排期、不通知。 */
class FakeCourseReminderControl : CourseReminderControl {

    var rescheduleCount = 0
        private set

    var cancelCount = 0
        private set

    var cancelActiveCount = 0
        private set

    var openSettingsCount = 0
        private set

    override fun reschedule() {
        rescheduleCount++
    }

    override fun cancel() {
        cancelCount++
    }

    override fun cancelActiveReminder() {
        cancelActiveCount++
    }

    override fun openSystemSettings() {
        openSettingsCount++
    }

    var exactAlarmAllowed = true
    var openExactAlarmSettingsCount = 0
        private set

    override fun canScheduleExactReminders(): Boolean = exactAlarmAllowed

    override fun openExactAlarmSettings() { openExactAlarmSettingsCount++ }
}

/** [AppDataReset] 的 fake：只记下被调用了几次。 */
class FakeAppDataReset : AppDataReset {

    var clearCount = 0
        private set

    override suspend fun clearAll() {
        clearCount++
    }
}

/** 只记内存的凭据保险箱 fake：自定义校园卡密码。 */
open class FakeCredentialVault(var campusCardPassword: String? = null) : CredentialVault {
    /** 置 true 后校园卡密码的写/删抛异常（模拟 Keystore 不可用）。 */
    var failCampusCardWrites = false

    override fun saveWisdomPassword(password: String) = Unit

    override fun wisdomPassword(): String? = null

    override fun clearWisdomPassword() = Unit

    override fun saveCampusCardPassword(password: String) {
        if (failCampusCardWrites) throw IllegalStateException("keystore unavailable")
        campusCardPassword = password
    }

    override fun campusCardPassword(): String? = campusCardPassword

    override fun clearCampusCardPassword() {
        if (failCampusCardWrites) throw IllegalStateException("keystore unavailable")
        campusCardPassword = null
    }
}

/** [CampusCardSessionControl] 的 fake：只记下失效次数。 */
class FakeCampusCardSessionControl : CampusCardSessionControl {

    var invalidateCount = 0
        private set

    override fun invalidate() {
        invalidateCount++
    }
}

/** [AppUpdateGateway] 的 fake：固定版本名与更新说明，也可以让它失败。 */
class FakeAppUpdateGateway(
    override val currentVersionName: String? = "3.3.0",
    var changelogText: String = "更新说明",
    var failChangelog: Boolean = false
) : AppUpdateGateway {

    var changelogCount = 0
        private set

    override suspend fun changelog(): String {
        changelogCount++
        if (failChangelog) throw IllegalStateException("offline")
        return changelogText
    }
}
