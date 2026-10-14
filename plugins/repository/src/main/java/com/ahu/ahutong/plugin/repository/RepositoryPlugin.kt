package com.ahu.ahutong.plugin.repository

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginHostV2
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginLifecycle
import com.ahu.ahutong.core.plugin.PluginMeta
import com.ahu.ahutong.core.plugin.PluginServiceRegistry
import com.ahu.ahutong.core.plugin.LocalPluginNavigation
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import kotlinx.coroutines.Dispatchers

class RepositoryPlugin : AhuPlugin, PluginLifecycle {
    override val meta = PluginMeta("repository", "学习资料", "课程资料、文档阅读与下载管理",
        PluginIcon.Resource(0), 0xFF79639C, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private var services: PluginServiceRegistry? = null
    override fun onCreate(environment: PluginEnvironment) { services = environment.services }
    override fun onDestroy() { services = null }

    @Composable
    override fun Entry(host: PluginHostServices) {
        val factory = remember(host) {
            RepositoryManager.initialize(host)
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RepositoryViewModel(
                    ManagerRepositoryIndex(host.appContext), AndroidRepositoryFileAccess(host.appContext), Dispatchers.IO
                ) as T
            }
        }
        val model: RepositoryViewModel = viewModel(key = "plugin.repository", factory = factory)
        val navigation = rememberNavController()
        val externalNavigation = LocalPluginNavigation.current
        LaunchedEffect(externalNavigation?.route, externalNavigation?.requestId) {
            val requested = externalNavigation?.route.orEmpty()
            val route = when (requested.substringBefore('?')) {
                "repository_downloads", "repository_settings", "repository_dir" -> requested
                else -> REPOSITORY_ROUTE
            }
            navigation.popBackStack(REPOSITORY_ROUTE, false)
            if (route != REPOSITORY_ROUTE) navigation.navigate(route) { launchSingleTop = true }
        }
        val current by navigation.currentBackStackEntryAsState()
        val back = { if (!navigation.popBackStack()) (host as? PluginHostV2)?.close(); Unit }
        BackHandler(enabled = current?.destination?.route == REPOSITORY_ROUTE) { (host as? PluginHostV2)?.close() }
        NavHost(navigation, startDestination = REPOSITORY_ROUTE) {
            composable(REPOSITORY_ROUTE) {
                Repository(navigation, "", services?.resolve(BehaviorRecorder::class.java), model, back)
            }
            composable(REPOSITORY_DIRECTORY_ROUTE, arguments = listOf(navArgument(REPOSITORY_PATH_ARG) {
                type = NavType.StringType; defaultValue = ""
            })) { entry ->
                Repository(navigation, entry.arguments?.getString(REPOSITORY_PATH_ARG).orEmpty(),
                    services?.resolve(BehaviorRecorder::class.java), model, back)
            }
            composable("repository_downloads") { RepositoryDownloads(navigation, model) }
            composable("repository_settings") { RepositorySettings(navigation, host) }
        }
    }
}
