package com.ahu.ahutong.ui.state

import com.ahu.ahutong.core.storage.SettingsStore
import com.ahu.ahutong.core.storage.StartupThemePreferences
import com.ahu.ahutong.data.model.AppThemeMode
import com.ahu.ahutong.data.model.DEFAULT_THEME_COLOR
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.data.model.CampusCardProvider
import com.ahu.ahutong.personalization.bootstrap.BootstrapContributionStatus
import com.ahu.ahutong.personalization.semantic.MutationId
import com.ahu.ahutong.personalization.settings.PersonalizationSettings
import com.ahu.ahutong.testing.FakePersonalizationSettings
import com.ahu.ahutong.testing.FakeCourseReminderControl
import com.ahu.ahutong.testing.FakeBehaviorRecorder
import com.ahu.ahutong.testing.FakeRepositoryIndex
import com.ahu.ahutong.testing.FakeCredentialVault
import com.ahu.ahutong.testing.FakeCampusCardSessionControl
import com.ahu.ahutong.testing.FakeSettingsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 设置 ViewModel 的契约测试：两个协作方都是 fake，因此不需要设备、不碰 DataStore、
 * 也不会碰到端侧模型。这是 P3 对每个 feature 的要求——ViewModel 只依赖接口，
 * 于是它的行为可以被回归，而不是靠真机点一遍。
 *
 * 用 UnconfinedTestDispatcher：ViewModel 的收集与写入都是立即完成的，断言可以直接读结果。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val reminders = FakeCourseReminderControl()
    private val behavior = FakeBehaviorRecorder()
    private val credentials = FakeCredentialVault()
    private val sessionControl = FakeCampusCardSessionControl()

    private fun viewModel(
        store: SettingsStore = FakeSettingsStore(),
        personalization: FakePersonalizationSettings = FakePersonalizationSettings(),
        repository: FakeRepositoryIndex = FakeRepositoryIndex()
    ) = PreferencesViewModel(
        store,
        personalization,
        reminders,
        repository,
        behavior,
        credentials,
        sessionControl,
        UnconfinedTestDispatcher()
    )

    @Test
    fun `enabling waits for storage before scheduling and a following disable wins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = FakeSettingsStore()
        val writeGate = CompletableDeferred<Unit>()
        val delayedStore = object : SettingsStore by store {
            override suspend fun setCourseReminderEnabled(value: Boolean) {
                if (value) writeGate.await()
                store.setCourseReminderEnabled(value)
            }
        }
        val subject = viewModel(delayedStore)
        subject.setCourseReminderEnabled(true)
        runCurrent()
        assertFalse(store.courseReminderEnabled.value)
        assertEquals(0, reminders.rescheduleCount)

        subject.setCourseReminderEnabled(false)
        runCurrent()
        assertEquals(0, reminders.cancelCount)
        writeGate.complete(Unit)
        runCurrent()
        assertFalse(store.courseReminderEnabled.value)
        assertEquals(1, reminders.rescheduleCount)
        assertEquals(1, reminders.cancelCount)
    }

    @Test
    fun `granting exact alarm access refreshes state and reschedules only once`() {
        reminders.exactAlarmAllowed = false
        val subject = viewModel()
        assertFalse(subject.courseReminderExactAlarmAllowed.value)
        subject.openCourseReminderExactAlarmSettings()
        assertEquals(1, reminders.openExactAlarmSettingsCount)
        reminders.exactAlarmAllowed = true
        subject.refreshCourseReminderAlarmAccess()
        subject.refreshCourseReminderAlarmAccess()
        assertTrue(subject.courseReminderExactAlarmAllowed.value)
        assertEquals(1, reminders.rescheduleCount)
    }

    @Test
    fun `the acceleration sources come from the repository port`() {
        val subject = viewModel(
            FakeSettingsStore(),
            FakePersonalizationSettings(),
            FakeRepositoryIndex(
                listOf(
                    com.ahu.ahutong.data.repository.RepositoryAccelerationSource(
                        id = "only",
                        name = "Only",
                        description = "唯一选项"
                    )
                )
            )
        )

        assertEquals(listOf("only"), subject.accelerationSources.map { it.id })
    }

    @Test
    fun `the reminder commands reach the background boundary`() {
        val subject = viewModel(FakeSettingsStore(), FakePersonalizationSettings())

        subject.rescheduleCourseReminders()
        subject.cancelCourseReminders()
        subject.dismissActiveCourseReminder()
        subject.openCourseReminderSystemSettings()

        assertEquals(1, reminders.rescheduleCount)
        assertEquals(1, reminders.cancelCount)
        assertEquals(1, reminders.cancelActiveCount)
        assertEquals(1, reminders.openSettingsCount)
    }

    @Test
    fun `the state the screen reads comes from the store`() {
        val store = FakeSettingsStore().apply {
            showQRCode.value = true
            isShowAllCourse.value = true
            courseReminderEnabled.value = true
            repositoryAccelerationSource.value = "github"
        }

        val viewModel = viewModel(store, FakePersonalizationSettings())

        assertTrue(viewModel.showQRCode.value)
        assertTrue(viewModel.isShowAllCourse.value)
        assertTrue(viewModel.courseReminderEnabled.value)
        assertEquals("github", viewModel.repositoryAccelerationSource.value)
    }

    @Test
    fun `the startup mirror decides the first frame before DataStore answers`() {
        // 这一例看的是"设置还没回答"的那一帧，所以换成 StandardTestDispatcher：
        // 收集器在 advanceUntilIdle 之前不会运行，第一帧只能来自启动镜像。
        Dispatchers.setMain(StandardTestDispatcher())

        val store = FakeSettingsStore().apply {
            startupTheme = StartupThemePreferences(AppUiTheme.MIUIX, "#123456", AppThemeMode.DARK)
        }

        val viewModel = viewModel(store, FakePersonalizationSettings())

        assertTrue(viewModel.isUiThemePreferenceReady.value)
        assertEquals(AppUiTheme.MIUIX, viewModel.appUiTheme.value)
        assertEquals("#123456", viewModel.themeColor.value)
        assertEquals(AppThemeMode.DARK, viewModel.appThemeMode.value)
    }

    @Test
    fun `without a mirror the screen waits for the store and assumes the default`() {
        Dispatchers.setMain(StandardTestDispatcher())

        val viewModel = viewModel(FakeSettingsStore(), FakePersonalizationSettings())

        assertFalse(viewModel.isUiThemePreferenceReady.value)
        assertEquals(AppUiTheme.RADIANT, viewModel.appUiTheme.value)
        assertNull(viewModel.themeColor.value)
    }

    @Test
    fun `changing the QR code persists it and reports the committed mutation`() {
        val store = FakeSettingsStore()
        val personalization = FakePersonalizationSettings()
        val viewModel = viewModel(store, personalization)

        viewModel.setShowQRCode(true)

        assertTrue(store.showQRCode.value)
        assertEquals(1, behavior.reports.size)
        val report = behavior.reports.single()
        assertEquals(MutationId.HOME_DEFAULT_QR_CHANGED, report.mutationId)
        assertEquals(false, report.oldValue)
        assertEquals(true, report.newValue)
    }

    @Test
    fun `the reported old value follows the state the screen is showing`() {
        val store = FakeSettingsStore().apply { showQRCode.value = true }
        val personalization = FakePersonalizationSettings()
        val viewModel = viewModel(store, personalization)

        viewModel.setShowQRCode(false)

        assertEquals(true, behavior.reports.single().oldValue)
        assertEquals(false, behavior.reports.single().newValue)
    }

    @Test
    fun `turning personalization off dismisses the current suggestion`() {
        val store = FakeSettingsStore()
        val personalization = FakePersonalizationSettings()
        val viewModel = viewModel(store, personalization)

        viewModel.setPersonalizationEnabled(false)

        assertFalse(store.personalizationEnabled.value)
        assertEquals(1, personalization.dismissedCount)
    }

    @Test
    fun `turning predictive prefetch off also clears wifi-only`() {
        val store = FakeSettingsStore().apply {
            predictivePrefetchEnabled.value = true
            wifiOnlyPrefetch.value = true
        }
        val personalization = FakePersonalizationSettings()
        val viewModel = viewModel(store, personalization)

        viewModel.setPredictivePrefetchEnabled(false)

        assertFalse(store.predictivePrefetchEnabled.value)
        assertFalse(store.wifiOnlyPrefetch.value)
        assertEquals(1, personalization.cancelledPrefetchCount)
    }

    @Test
    fun `wifi-only cannot be switched on while predictive prefetch is off`() {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store, FakePersonalizationSettings())

        viewModel.setWifiOnlyPrefetch(true)

        assertFalse(store.wifiOnlyPrefetch.value)
    }

    @Test
    fun `switching to the Miuix theme stores its default colour and reports once`() {
        val store = FakeSettingsStore()
        val personalization = FakePersonalizationSettings()
        val viewModel = viewModel(store, personalization)

        viewModel.setAppUiTheme(AppUiTheme.MIUIX)

        assertEquals(AppUiTheme.MIUIX, viewModel.appUiTheme.value)
        assertEquals(DEFAULT_THEME_COLOR, store.themeColor.value)
        assertEquals(1, behavior.reports.size)
        assertEquals("UI_STYLE_CHANGED", behavior.reports.single().coarseValueBucket)
    }

    @Test
    fun `clearing the learning record goes through the port`() {
        val personalization = FakePersonalizationSettings()
        val viewModel = viewModel(FakeSettingsStore(), personalization)

        viewModel.clearPersonalizationLearning()

        assertEquals(1, personalization.clearLearningCount)
    }

    @Test
    fun `the contribution status is the one the port exposes`() {
        val personalization = FakePersonalizationSettings().apply {
            status.value = BootstrapContributionStatus(enabled = true, pendingExamples = 3)
        }

        val viewModel = viewModel(FakeSettingsStore(), personalization)

        assertEquals(3, viewModel.bootstrapContributionStatus.value.pendingExamples)
    }

    @Test
    fun `switching the campus card provider persists it`() {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store, FakePersonalizationSettings())

        assertEquals(CampusCardProvider.AHU, viewModel.campusCardProvider.value)

        viewModel.setCampusCardProvider(CampusCardProvider.CUSTOM)

        assertEquals(CampusCardProvider.CUSTOM, store.campusCardProvider.value)
        assertEquals(CampusCardProvider.CUSTOM, viewModel.campusCardProvider.value)
        // 切换数据源必须作废内存会话，避免继续用上一数据源的凭据查询。
        assertEquals(1, sessionControl.invalidateCount)
    }

    @Test
    fun `saving campus card credentials stores account in settings and password in the vault`() {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store, FakePersonalizationSettings())

        viewModel.saveCampusCardCredentials("  20210001  ", "secret")

        assertEquals("20210001", store.campusCardCustomAccount.value)
        assertEquals("secret", credentials.campusCardPassword)
        assertTrue(viewModel.campusCardCustomPasswordSet.value)
        // 凭据变化必须作废旧会话。
        assertEquals(1, sessionControl.invalidateCount)
    }

    @Test
    fun `an empty password keeps the stored one while updating the account`() {
        val store = FakeSettingsStore()
        credentials.campusCardPassword = "keep-me"
        val viewModel = viewModel(store, FakePersonalizationSettings())

        viewModel.saveCampusCardCredentials("20210002", "")

        assertEquals("20210002", store.campusCardCustomAccount.value)
        assertEquals("keep-me", credentials.campusCardPassword)
        assertEquals(1, sessionControl.invalidateCount)
    }

    @Test
    fun `clearing campus card credentials wipes both the vault and the account`() {
        val store = FakeSettingsStore().apply { campusCardCustomAccount.value = "20210001" }
        credentials.campusCardPassword = "secret"
        val viewModel = viewModel(store, FakePersonalizationSettings())

        viewModel.clearCampusCardCredentials()

        assertNull(credentials.campusCardPassword)
        assertNull(store.campusCardCustomAccount.value)
        assertFalse(viewModel.campusCardCustomPasswordSet.value)
        assertEquals(1, sessionControl.invalidateCount)
    }

    @Test
    fun `a failed provider write rolls the switch back instead of crashing`() {
        val store = FakeSettingsStore().apply { failCampusCardWrites = true }
        val viewModel = viewModel(store, FakePersonalizationSettings())

        viewModel.setCampusCardProvider(CampusCardProvider.CUSTOM)

        // 写失败（此前会因未接住 IOException 直接崩）：乐观值必须回落到真实落盘值。
        assertEquals(CampusCardProvider.AHU, viewModel.campusCardProvider.value)
        assertEquals(CampusCardProvider.AHU, store.campusCardProvider.value)
    }

    @Test
    fun `a failed clear keeps the credential and never claims it was cleared`() {
        val store = FakeSettingsStore().apply { campusCardCustomAccount.value = "20210001" }
        credentials.campusCardPassword = "secret"
        val viewModel = viewModel(store, FakePersonalizationSettings())
        credentials.failCampusCardWrites = true

        viewModel.clearCampusCardCredentials()

        // 删除失败：密码仍在 Keystore，界面绝不能声称已清除，账号也要保持原值。
        assertEquals("secret", credentials.campusCardPassword)
        assertTrue(viewModel.campusCardCustomPasswordSet.value)
        assertEquals("20210001", viewModel.campusCardCustomAccount.value)
        assertEquals("20210001", store.campusCardCustomAccount.value)
    }

    @Test
    fun `a failed save does not claim the password was stored`() {
        val store = FakeSettingsStore()
        val viewModel = viewModel(store, FakePersonalizationSettings())
        credentials.failCampusCardWrites = true

        viewModel.saveCampusCardCredentials("20210001", "secret")

        // Keystore 写失败：既没有密码，也不能把状态标成"已配置"。
        assertNull(credentials.campusCardPassword)
        assertFalse(viewModel.campusCardCustomPasswordSet.value)
        assertNull(store.campusCardCustomAccount.value)
        assertEquals("", viewModel.campusCardCustomAccount.value)
    }
}
