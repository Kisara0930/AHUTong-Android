package com.ahu.ahutong.plugin.schedule

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.model.AcademicAccountType
import com.ahu.ahutong.data.model.Course
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.schedule.data.calendar.RepositoryScheduleHolidaySource
import com.ahu.ahutong.plugin.schedule.data.schedule.CurrentWeekResolver
import com.ahu.ahutong.plugin.schedule.data.schedule.ScheduleSectionTimes
import com.ahu.ahutong.plugin.schedule.data.schedule.ScheduleUiPrefs
import com.ahu.ahutong.plugin.schedule.ui.screen.main.Schedule
import com.ahu.ahutong.plugin.schedule.ui.screen.setup.Info
import com.ahu.ahutong.plugin.schedule.ui.screen.main.home.CourseStrip
import com.ahu.ahutong.plugin.schedule.ui.screen.main.home.AtAGlance
import com.ahu.ahutong.plugin.schedule.ui.state.PostgraduateScheduleViewModel
import com.ahu.ahutong.plugin.schedule.ui.state.RepositoryScheduleWeekConfig
import com.ahu.ahutong.plugin.schedule.ui.state.ScheduleViewModel
import com.ahu.ahutong.plugin.schedule.data.schedule.WidgetScheduleSnapshot
import com.ahu.ahutong.plugin.schedule.widget.GraduateWidgetSchedule
import com.ahu.ahutong.plugin.schedule.widget.ScheduleWidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class SchedulePlugin : AhuPlugin, PluginLifecycle, PluginContributions, PluginWorkHandler, PluginWidgetProvider, PluginReminderRenderer,
    PluginPrefetchHandler, PluginSemesterInfo {
    override val meta = PluginMeta("schedule", "课表", "本研课表、周次、课程提醒和首页课程", PluginIcon.Resource(0), 0xFF5579AA, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private var holidays: RepositoryScheduleHolidaySource? = null
    private var lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reminders = ScheduleReminderController()
    override fun onCreate(environment: PluginEnvironment) {
        PluginScope.environment = environment
        environment.services.publish(PluginSemesterInfo::class.java, this)
    }
    override suspend fun prefetch(actionId: String): Result<Unit> = try {
        require(actionId == "VIEW_SCHEDULE") { "课表组件不支持此预取动作" }
        val user = requireNotNull(SessionStore.currentUser()) { "未登录校园账号" }
        if (user.academicAccountType == AcademicAccountType.POSTGRADUATE) {
            com.ahu.ahutong.plugin.schedule.data.crawler.gmis.PostgraduateScheduleRepository.instance.current(user.xh)
        } else {
            val response = ScheduleRepository(onChanged = reminders::reschedule).fetch(false)
            check(response is com.ahu.ahutong.core.common.AhuResult.Success) { "课表预取失败：${response.errorOrNull()}" }
        }
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    override fun semesterWeek(epochDay: Long): Int? {
        val user = SessionStore.currentUser() ?: return null
        val date = java.time.LocalDate.ofEpochDay(epochDay)
        return if (user.academicAccountType == AcademicAccountType.POSTGRADUATE) {
            val snapshot = com.ahu.ahutong.plugin.schedule.data.crawler.gmis.PostgraduateScheduleRepository.instance.cachedCurrent(user.xh) ?: return null
            val monday = com.ahu.ahutong.plugin.schedule.data.schedule.PostgraduateTeachingWeek.parseStored(
                ScheduleCache.current().getPostgraduateWeekStart(snapshot.selectedTerm.code)) ?: return null
            com.ahu.ahutong.plugin.schedule.data.schedule.PostgraduateTeachingWeek.weekOn(monday, date)
        } else CurrentWeekResolver().resolveLocalConfig(date)?.config?.takeIf { it.isInSemester }?.week
    }
    override fun onEnable() {
        lifecycleScope.cancel()
        lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lifecycleScope.launch { prepareReminders() }
    }
    override fun onDisable() { lifecycleScope.cancel(); reminders.cancel(); holidays?.close(); holidays = null }
    override fun onAccountChanged() { reminders.cancel(); lifecycleScope.launch { prepareReminders() } }
    override fun onDestroy() = onDisable()
    override fun buildNotification(context: android.content.Context, reminder: PluginReminder): android.app.Notification? {
        val payload = reminder.extraJson?.let { runCatching { com.google.gson.Gson().fromJson(it, CourseReminderPayload::class.java) }.getOrNull() }
            ?: return null
        val date = payload.courseStartAtMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        val holiday = date?.let { holidays?.holidays?.value?.get(it) }
            ?.let { com.ahu.ahutong.plugin.schedule.data.schedule.scheduleHolidayNotice(it) }
        return CourseLiveUpdateHelper.build(context, payload, holiday)
    }
    override suspend fun run(taskId: String): PluginWorkResult = when(taskId) {
        "replan" -> { reminders.reschedule(); PluginWorkResult.SUCCESS }
        "refresh" -> {
            val user = SessionStore.currentUser()
            if (user?.academicAccountType == AcademicAccountType.POSTGRADUATE) {
                GraduateWidgetSchedule.instance.refresh(user)
                PluginWorkResult.SUCCESS
            } else when(ScheduleRepository(onChanged = reminders::reschedule).refreshCache()) {
                is com.ahu.ahutong.core.common.AhuResult.Success -> PluginWorkResult.SUCCESS
                is com.ahu.ahutong.core.common.AhuResult.Failure -> PluginWorkResult.RETRY
            }
        }
        else -> PluginWorkResult.FAILURE
    }.also { if (it == PluginWorkResult.SUCCESS) PluginScope.services.resolve(PluginWidgetService::class.java)?.update("schedule") }

    private fun holidaySource(): RepositoryScheduleHolidaySource = synchronized(this) {
        holidays ?: RepositoryScheduleHolidaySource(PluginScope.environment.context).also { holidays = it }
    }

    private suspend fun migrateReminderSettings() {
        if (!SessionStore.isLoggedIn()) return
        val cache = ScheduleCache.current()
        if (cache.reminderSettingsMigrated()) return
        val legacy = PluginScope.services.resolve(com.ahu.ahutong.core.storage.CourseReminderSettings::class.java) ?: return
        cache.inheritReminderSettings(legacy.courseReminderEnabled.first(), legacy.courseReminderLiveCountdownEnabled.first())
    }

    private suspend fun prepareReminders() {
        try { migrateReminderSettings(); reminders.reschedule() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { android.util.Log.w("SchedulePlugin", "Reminder state could not be restored", failure) }
    }

    override fun renderWidget(hostContext: android.content.Context, appWidgetId: Int, options: android.os.Bundle): android.widget.RemoteViews {
        val user = SessionStore.currentUser()
        val snapshot = if (user?.academicAccountType == AcademicAccountType.POSTGRADUATE) GraduateWidgetSchedule.instance.cached(user)
            else {
                val cache = ScheduleCache.current()
                val resolver = CurrentWeekResolver(cache)
                val term = cache.getSchoolTerm()
                WidgetScheduleSnapshot(term?.let(cache::getSchedule).orEmpty(),
                    resolver.resolveLocalConfig()?.config ?: resolver.defaultConfig().config,
                    term?.let(cache::getScheduleFetchedAt), unavailableMessage = if (user == null) "请先登录校园账号" else null)
            }
        // Rendering is cache-only: no login, timetable refresh or holiday network request here.
        val holiday = holidays?.holidays?.value?.get(java.time.LocalDate.now())
        return ScheduleWidgetRenderer.render(hostContext, appWidgetId, options, snapshot, holiday)
    }

    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        val user = SessionStore.currentUser()
        if (user == null) { Button(onClick = { navigation?.open("account") }) { Text("请先登录校园账号") }; return }
        var settings by rememberSaveable { mutableStateOf(false) }
        var info by rememberSaveable(navigation?.route, navigation?.requestId) { mutableStateOf(navigation?.route == "info" || navigation?.route?.endsWith("/info") == true) }
        val factory = remember(generation) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = when(modelClass) {
                ScheduleViewModel::class.java -> ScheduleViewModel(ScheduleRepository(onChanged = reminders::reschedule),
                    RepositoryScheduleWeekConfig(), SessionStore, reminders, holidaySource())
                PostgraduateScheduleViewModel::class.java -> PostgraduateScheduleViewModel()
                else -> error("未知课表状态类型")
            } as T
        } }
        val model: ScheduleViewModel = viewModel(key = "schedule.$generation", factory = factory)
        val graduate: PostgraduateScheduleViewModel? = if (user.academicAccountType == AcademicAccountType.POSTGRADUATE)
            viewModel(key = "schedule.gmis.$generation", factory = factory) else null
        LaunchedEffect(info, generation, navigation?.requestId) {
            if (info) {
                if (graduate != null) graduate.open(user.xh) else model.loadConfig()
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                info && graduate == null -> Info(model) { info = false; reminders.reschedule() }
                info && graduate != null -> GraduateInfo(graduate) { info = false }
                settings -> ScheduleSettings(reminders)
                else -> Schedule(model, OptionalBehavior, graduate, user.xh)
            }
        }
    }

    @Composable override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        val generation by AccountGeneration.changes.collectAsState()
        var courses by remember(generation) { mutableStateOf(emptyList<Course>()) }
        var config by remember(generation) { mutableStateOf<com.ahu.ahutong.data.model.ScheduleConfigBean?>(null) }
        var unavailable by remember(generation) { mutableStateOf<String?>(null) }
        var currentTime by remember { mutableStateOf(LocalDateTime.now()) }
        val calendar = remember { holidaySource() }
        val holidayMap by calendar.holidays.collectAsState()
        LaunchedEffect(generation) {
            val user = SessionStore.currentUser()
            if (user == null) { unavailable = "登录校园账号后查看今日课程"; return@LaunchedEffect }
            if (user.academicAccountType == AcademicAccountType.POSTGRADUATE) {
                runCatching { withContext(Dispatchers.IO) {
                    com.ahu.ahutong.plugin.schedule.data.crawler.gmis.PostgraduateScheduleRepository.instance.current(user.xh)
                } }
                val cached = GraduateWidgetSchedule.instance.cached(user)
                courses = cached.courses; config = cached.config; unavailable = cached.unavailableMessage
            } else {
                val result = withContext(Dispatchers.IO) {
                    val position = CurrentWeekResolver().resolveLocalFirst().config
                    val fetched = ScheduleRepository(onChanged = reminders::reschedule).fetch(false)
                    reminders.reschedule()
                    position to fetched
                }
                courses = result.second.valueOrNull().orEmpty(); config = result.first
            }
        }
        LaunchedEffect(Unit) { while (true) { currentTime = LocalDateTime.now(); delay(30_000) } }
        LaunchedEffect(currentTime.toLocalDate()) { calendar.load(setOf(currentTime.year), false) }
        val currentMinutes = currentTime.hour * 60 + currentTime.minute
        val week = config?.week
        val today = courses.filter { week != null && week in it.weekIndexes && it.weekday == currentTime.dayOfWeek.value }
            .sortedBy { it.startTime }
        if (unavailable != null) {
            Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(unavailable.orEmpty(), modifier = Modifier.padding(18.dp))
            }
        } else {
            AtAGlance(today, currentMinutes, currentTime.toLocalDate().toString(), onOpen,
                isInSemester = config?.isInSemester != false)
            Spacer(Modifier.height(16.dp))
            val tomorrowDate = currentTime.toLocalDate().plusDays(1)
            val tomorrowWeek = week?.plus(if (tomorrowDate.dayOfWeek.value == 1) 1 else 0)
            val tomorrow = courses.filter { tomorrowWeek != null && tomorrowWeek in it.weekIndexes && it.weekday == tomorrowDate.dayOfWeek.value }
                .sortedBy { it.startTime }
            val showTomorrow = today.isNotEmpty() && today.all { ScheduleSectionTimes.getCourseTimeRangeInMinutes(it).last < currentMinutes } && tomorrow.isNotEmpty()
            if ((if (showTomorrow) tomorrow else today).isNotEmpty()) CourseStrip(if (showTomorrow) tomorrow else today, currentMinutes, onOpen,
                isTomorrow = showTomorrow, holiday = holidayMap[if (showTomorrow) tomorrowDate else currentTime.toLocalDate()])
        }
    }
}

@Composable
private fun GraduateInfo(model: PostgraduateScheduleViewModel, onSaved: () -> Unit) {
    val state by model.state.collectAsState()
    var week by rememberSaveable(state.selectedTerm?.code, state.firstWeekMonday) { mutableStateOf(
        state.firstWeekMonday?.let { com.ahu.ahutong.plugin.schedule.data.schedule.PostgraduateTeachingWeek.weekOn(it, state.today).coerceIn(1, 60).toString() } ?: "1") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("研究生学期与教学周", style = MaterialTheme.typography.titleLarge)
        state.terms.forEach { term ->
            TextButton(onClick = { model.selectTerm(term) }) { Text((if (state.selectedTerm?.code == term.code) "✓ " else "") + term.name) }
        }
        OutlinedTextField(week, { week = it.filter(Char::isDigit).take(2) }, label = { Text("当前教学周（1–60）") })
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val number = week.toIntOrNull()
            if (number == null || number !in 1..60 || !model.saveCurrentWeek(number)) error = "请先选择学期并填写有效教学周"
            else onSaved()
        }, enabled = !state.loading && state.selectedTerm != null) { Text("保存") }
    }
}

@Composable
private fun ScheduleSettings(reminders: ScheduleReminderController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val cache = remember { ScheduleCache.current() }
    var all by remember { mutableStateOf(cache.isShowAllCourse()) }
    var enabled by remember { mutableStateOf(cache.remindersEnabled()) }
    var minutes by remember { mutableStateOf(cache.reminderMinutes().toString()) }
    var nowLine by remember { mutableStateOf(ScheduleUiPrefs.isNowTimelineVisible()) }
    var liveCountdown by remember { mutableStateOf(cache.liveCountdownEnabled()) }
    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    var notificationsGranted by remember { mutableStateOf(canNotify()) }
    var exactAlarmGranted by remember { mutableStateOf(reminders.canScheduleExactReminders()) }
    var pendingEnable by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted && canNotify()
        if (notificationsGranted) {
            enabled = true; cache.setRemindersEnabled(true); reminders.reschedule(); permissionMessage = null
        } else {
            enabled = false; cache.setRemindersEnabled(false); reminders.cancel()
            permissionMessage = "通知权限未授予，课前提醒保持关闭。"
        }
        pendingEnable = false
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            notificationsGranted = canNotify()
            exactAlarmGranted = reminders.canScheduleExactReminders()
            if (pendingEnable && notificationsGranted) {
                enabled = true; cache.setRemindersEnabled(true); pendingEnable = false
            }
            if (enabled && notificationsGranted) reminders.reschedule()
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row { Checkbox(all, { all = it; cache.setShowAllCourse(it) }); Text("显示非本周课程") }
        Row { Checkbox(nowLine, { nowLine = it; ScheduleUiPrefs.setNowTimelineVisible(it) }); Text("显示当前时间线") }
        Row { Checkbox(enabled, { requested ->
            if (!requested) { enabled = false; pendingEnable = false; cache.setRemindersEnabled(false); reminders.cancel() }
            else if (canNotify()) { enabled = true; cache.setRemindersEnabled(true); reminders.reschedule() }
            else {
                pendingEnable = true
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else reminders.openSystemSettings()
            }
        }); Text("课前提醒") }
        if (!notificationsGranted) Text("通知权限尚未开启，系统不会显示课程提醒。", color = MaterialTheme.colorScheme.error)
        permissionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (enabled && !exactAlarmGranted) Text("尚未允许精确闹钟，目前按系统普通调度，提醒可能延迟。", color = MaterialTheme.colorScheme.error)
        Row { Checkbox(liveCountdown, { liveCountdown = it; cache.setLiveCountdownEnabled(it); reminders.reschedule() }); Text("实时倒计时与岛卡（系统支持时）") }
        OutlinedTextField(minutes, { value -> if(value.all(Char::isDigit) && value.length <= 3) minutes = value }, label = { Text("提前分钟（0–120）") })
        Button(onClick = { cache.setReminderMinutes(minutes.toIntOrNull() ?: 10); reminders.reschedule() }) { Text("保存提醒时间") }
        OutlinedButton(onClick = reminders::openSystemSettings) { Text("通知权限设置") }
        if (!exactAlarmGranted) OutlinedButton(onClick = reminders::openExactAlarmSettings) { Text("允许准时提醒") }
    }
}
