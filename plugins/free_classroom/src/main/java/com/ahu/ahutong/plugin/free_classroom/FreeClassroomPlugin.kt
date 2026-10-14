package com.ahu.ahutong.plugin.free_classroom

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.plugin.free_classroom.ui.screen.main.FreeClassroom
import com.ahu.ahutong.plugin.free_classroom.ui.state.FreeClassroomViewModel

class FreeClassroomPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("free_classroom", "空闲教室", "按校区、楼栋和时段查找教室", PluginIcon.Resource(0), 0xFF457BAD, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    override fun onCreate(environment: PluginEnvironment) { PluginScope.environment = environment }
    @Composable override fun Entry(host: PluginHostServices) {
        val generation by AccountGeneration.changes.collectAsState()
        val navigation = LocalPluginNavigation.current
        if (!SessionStore.isLoggedIn()) {
            Button(onClick = { navigation?.open("account") }) { Text("请先登录校园账号") }
            return
        }
        val factory = remember(generation) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FreeClassroomViewModel() as T
        } }
        val model: FreeClassroomViewModel = viewModel(key = "free_classroom.$generation", factory = factory)
        FreeClassroom(onBack = { (host as? PluginHostV2)?.close() }, freeClassroomViewModel = model)
    }
}
