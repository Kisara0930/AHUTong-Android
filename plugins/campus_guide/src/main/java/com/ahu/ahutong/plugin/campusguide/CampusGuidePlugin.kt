package com.ahu.ahutong.plugin.campusguide

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginHostV2
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginMeta
import com.ahu.ahutong.core.plugin.PluginLifecycle
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginPrefetchHandler
import com.ahu.ahutong.core.plugin.LocalPluginNavigation
import com.ahu.ahutong.ui.components.AppButton
import com.ahu.ahutong.ui.components.AppPageScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CampusGuidePlugin : AhuPlugin, PluginLifecycle, PluginPrefetchHandler {
    override val meta = PluginMeta("campus_guide", "校园指南", "校历、电话本与菜鸟身份码",
        PluginIcon.Resource(0), 0xFF4B8269, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private var backgroundHost: PluginHostServices? = null
    override fun onCreate(environment: PluginEnvironment) { backgroundHost = environment.host }
    override fun onDestroy() { backgroundHost = null }

    override suspend fun prefetch(actionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (actionId != "VIEW_SCHOOL_CALENDAR") return@withContext Result.failure(IllegalArgumentException("不支持此预取动作"))
        try {
            val host = checkNotNull(backgroundHost) { "宿主预取能力不可用" }
            val gateway = CalendarGateway(host)
            val catalog = try { gateway.getSchoolCalendarYears() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            val years = SchoolCalendarYearPolicy.normalize(catalog?.years.orEmpty())
            val year = SchoolCalendarYearPolicy.select(years, catalog?.latestYear, null)
            val name = year?.let(SchoolCalendarYearPolicy::cacheFileName) ?: "xiaoli.jpg"
            if (!FileUtils.isValidImage(FileUtils.getImageFile(host.appContext, name))) {
                gateway.getSchoolCalendar(year).use { response ->
                    check(response.isSuccessful) { "校历预取失败 (${response.code})" }
                    val saved = FileUtils.saveResponseBodyToFileAtomically(host.appContext,
                        requireNotNull(response.body), name)
                    check(saved != null && FileUtils.isValidImage(saved)) { "校历预取没有生成有效图片" }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    @Composable
    override fun Entry(host: PluginHostServices) {
        var page by rememberSaveable { mutableStateOf("guide") }
        val context = LocalContext.current
        val navigation = LocalPluginNavigation.current
        val openIdentity = {
            val url = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code"
            val scheme = "tbopen://m.taobao.com/tbopen/index.html?action=ali.open.nav&module=h5&bootImage=0&h5Url=" +
                java.net.URLEncoder.encode(url, "UTF-8")
            val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scheme))) }.isSuccess
            if (!opened && runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure) {
                Toast.makeText(context, "无法打开身份码，请安装淘宝或浏览器", Toast.LENGTH_SHORT).show()
            }
        }
        LaunchedEffect(navigation?.route, navigation?.requestId) {
            page = when (navigation?.route?.substringBefore('?')) {
                "school_calendar", "calendar" -> "calendar"
                "phone_book", "phone" -> "phone"
                "identity_code" -> { openIdentity(); "guide" }
                else -> "guide"
            }
        }
        val gateway = remember(host) { CalendarGateway(host) }
        val back = { page = "guide" }
        BackHandler(enabled = page != "guide", onBack = back)
        when (page) {
            "calendar" -> SchoolCalendar(gateway, back)
            "phone" -> PhoneBook(back)
            else -> AppPageScaffold(
                title = "校园指南", onBack = { (host as? PluginHostV2)?.close() },
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = {
                    AppButton(onClick = { page = "calendar" }, modifier = Modifier.fillMaxWidth()) { Text("校历") }
                    AppButton(onClick = { page = "phone" }, modifier = Modifier.fillMaxWidth()) { Text("电话本") }
                    AppButton(onClick = openIdentity, modifier = Modifier.fillMaxWidth()) { Text("菜鸟身份码") }
                }
            )
        }
    }
}
