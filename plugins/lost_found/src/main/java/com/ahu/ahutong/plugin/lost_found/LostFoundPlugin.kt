package com.ahu.ahutong.plugin.lost_found

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.data.session.CampusSessionService
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.preset.PresetSuggestions
import com.ahu.ahutong.ui.screen.main.LostFound
import com.ahu.ahutong.ui.state.LostFoundViewModel

class LostFoundPlugin : AhuPlugin, PluginLifecycle, PluginContributions, PluginPrefetchHandler {
    override val meta = PluginMeta("lost_found", "失物招领", "查询、筛选、图片详情、发布与我的帖子",
        PluginIcon.Resource(0), 0xFFB39B68, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var environment: PluginEnvironment
    private val repositories = java.util.concurrent.CopyOnWriteArrayList<LostFoundRepository>()
    @Volatile private var enabled = false
    override fun onCreate(environment: PluginEnvironment) { this.environment = environment }
    override fun onEnable() { enabled = true }
    override fun onDisable() { enabled = false; repositories.forEach { it.close() }; repositories.clear() }
    override fun onDestroy() { onDisable() }

    override suspend fun prefetch(actionId: String): Result<Unit> {
        if (actionId != "OPEN_LOST_FOUND" || !enabled) return Result.failure(IllegalStateException("此功能不支持预取或已停用"))
        val accountId = SessionStore.currentUser()?.xh ?: return Result.failure(IllegalStateException("请先登录"))
        val generation = AccountGeneration.current
        val campus = environment.services.resolve(CampusSessionService::class.java)
            ?: return Result.failure(IllegalStateException("账户组件未就绪"))
        val guard = { check(enabled && AccountGeneration.current == generation && SessionStore.currentUser()?.xh == accountId) }
        val repository = LostFoundRepository(campus.client(CampusRealm.WISDOM), accountId, guard,
            refresh = { guard(); campus.refresh(CampusRealm.WISDOM).also { guard() } })
        repositories += repository
        return try {
            val response = repository.getLostFoundList(1, 20, 1)
            val payload = response.valueOrNull()
                ?: return Result.failure(IllegalStateException("失物招领预取未完成"))
            guard()
            LostFoundCache(accountId, guard).saveLostFoundList(1, payload.data.list)
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        finally { repository.close(); repositories.remove(repository) }
    }

    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val account by SessionStore.accountIdUpdates().collectAsState()
        val campus = environment.services.resolve(CampusSessionService::class.java)
        val accountId = account
        if (campus == null || accountId.isNullOrBlank()) {
            androidx.compose.material3.Text("请先登录智慧安大")
            return
        }
        val factory = remember(generation, accountId, campus) {
            val guard = { check(enabled && AccountGeneration.current == generation && SessionStore.currentUser()?.xh == accountId) { "账号已切换或组件已停用" } }
            val repository = LostFoundRepository(campus.client(CampusRealm.WISDOM), accountId, guard,
                refresh = { guard(); campus.refresh(CampusRealm.WISDOM).also { guard() } })
            repositories += repository
            val cache = LostFoundCache(accountId, guard)
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = LostFoundViewModel(repository, cache,
                    recorder = { environment.services.resolve(BehaviorRecorder::class.java) },
                    presets = { environment.services.resolve(PresetSuggestions::class.java) }) as T
            }
        }
        val model: LostFoundViewModel = viewModel(key = "plugin.lost_found.$generation", factory = factory)
        LostFound(onBack = { (host as? PluginHostV2)?.close() }, lostFoundViewModel = model)
    }
    @Composable override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        androidx.compose.material3.TextButton(onClick = onOpen) { androidx.compose.material3.Text("失物招领") }
    }
}
