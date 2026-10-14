package com.ahu.ahutong.plugin.grade_exam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.grade_exam.ui.screen.main.Grade
import com.ahu.ahutong.plugin.grade_exam.ui.screen.main.Exam
import com.ahu.ahutong.plugin.grade_exam.ui.state.GradeViewModel
import com.ahu.ahutong.plugin.grade_exam.ui.state.ExamViewModel

class GradeExamPlugin : AhuPlugin, PluginLifecycle, PluginPrefetchHandler {
    override val meta = PluginMeta("grade_exam", "成绩与考试", "成绩、多专业档案、绩点排名和考试安排", PluginIcon.Resource(0), 0xFF5D73B3, "1.0.1", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    override fun onCreate(environment: PluginEnvironment) { PluginScope.environment = environment }
    override suspend fun prefetch(actionId: String): Result<Unit> = try {
        val user = requireNotNull(SessionStore.currentUser()) { "未登录校园账号" }
        val repository = GradeRepository()
        val response = when (actionId) {
            "VIEW_GRADES" -> repository.fetchGrade(false)
            "VIEW_EXAM_ROOM" -> repository.fetchExams(false, user.xh, user.name.orEmpty())
            else -> error("成绩考务组件不支持此预取动作")
        }
        check(response is com.ahu.ahutong.core.common.AhuResult.Success) { "成绩考务预取失败：${response.errorOrNull()}" }
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }
    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (!SessionStore.isLoggedIn()) {
            Button(onClick = { navigation?.open("account") }) { Text("请先登录校园账号") }; return
        }
        var showExam by rememberSaveable { mutableStateOf(navigation?.route == "exam") }
        LaunchedEffect(navigation?.route, navigation?.requestId) { showExam = navigation?.route == "exam" }
        val factory = remember(generation) { object : ViewModelProvider.Factory {
            private val source = GradeRepository()
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = when(modelClass) {
                GradeViewModel::class.java -> GradeViewModel(OptionalBehavior, OptionalPresets, source, SessionStore)
                ExamViewModel::class.java -> ExamViewModel(source, SessionStore, OptionalBehavior)
                else -> error("未知成绩组件状态类型")
            } as T
        } }
        Column {
            if (showExam) Exam(viewModel<ExamViewModel>(key = "exam.$generation", factory = factory), onBack = { (host as? PluginHostV2)?.close() })
            else Grade(viewModel<GradeViewModel>(key = "grade.$generation", factory = factory),
                onNavigateToEvaluation = { navigation?.open("evaluation") }, onBack = { (host as? PluginHostV2)?.close() })
        }
    }
}
