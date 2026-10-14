package com.ahu.ahutong.core.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Business cards use the same loaded implementation and lifecycle as their page. */
interface PluginContributions {
    @Composable
    fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {}
    /** Optional in-app notices; called only while the plugin is enabled. */
    @Composable
    fun Foreground(host: PluginHostServices) {}
}

/** The shell owns navigation; plugins declare destinations rather than a host Activity. */
interface PluginNavigation {
    val route: String
    /** Distinguishes repeated requests for the same route while a plugin keeps local navigation. */
    val requestId: Long get() = 0L
    fun open(route: String)
    fun back()
}

val LocalPluginNavigation = staticCompositionLocalOf<PluginNavigation?> { null }

/** Read-only prefetch owned by the target feature, never by the prediction engine. */
interface PluginPrefetchHandler {
    suspend fun prefetch(actionId: String): Result<Unit>
}

interface PluginSemesterInfo {
    fun semesterWeek(epochDay: Long): Int?
}
