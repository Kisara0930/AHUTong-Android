package com.ahu.ahutong.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.ahu.ahutong.ui.components.*
import com.ahu.ahutong.data.session.SessionStore
import com.kyant.capsule.ContinuousCapsule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.BuildConfig
import com.ahu.ahutong.PluginApplication
import com.ahu.ahutong.R
import com.ahu.ahutong.data.model.AppThemeMode
import com.ahu.ahutong.update.HostUpdateClient
import com.ahu.ahutong.update.HostUpdateMetadata
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
fun HostSettings(marketUrl: String, onNavigate: (String) -> Unit = {}, onManage: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as PluginApplication
    val scope = rememberCoroutineScope()
    val mode by app.preferences.themeMode.collectAsState(AppThemeMode.FOLLOW_SYSTEM)
    val secureKeyboard by app.preferences.useBuiltInSecurePasswordKeyboard.collectAsState(true)
    val activationError by app.activationError.collectAsState()
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showContributors by remember { mutableStateOf(false) }
    var showClearData by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<HostUpdateMetadata?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var client by remember { mutableStateOf<HostUpdateClient?>(null) }
    // A cancelled network call still owns its cleanup until the coroutine actually exits.
    val busy = job != null
    DisposableEffect(Unit) { onDispose { client?.cancel(); job?.cancel() } }
    LaunchedEffect(marketUrl) { client?.cancel(); job?.cancel(); update = null; downloaded = null; progress = null }

    SettingsBackdropContainer(Modifier.fillMaxSize()) { backdrop ->
    SettingsPageLayout(title = "设置", backdrop = backdrop) {
        SettingsHeroCard(backdrop = backdrop, onClick = {}, modifier = Modifier.padding(horizontal = 16.dp)) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), null,
                Modifier.size(64.dp).clip(ContinuousCapsule).background(Color.White).scale(1.65f))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("安大通", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val user = SessionStore.currentUser()
        SettingsSection(title = "账户", backdrop = backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            user?.let { SettingsInfoRow(it.name.orEmpty(), subtitle = if (it.academicAccountType == com.ahu.ahutong.data.model.AcademicAccountType.POSTGRADUATE) "研究生账号" else "本科生账号") }
            SettingsActionRow("重新登录", { onNavigate("login") }, leadingIcon = Icons.Outlined.Person)
            SettingsActionRow("切换账号", { onNavigate("accounts") }, subtitle = "快速登录已保存的账号", leadingIcon = Icons.Outlined.PeopleOutline, showDivider = false)
        }
        SettingsSection(title = "应用", backdrop = backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            SettingsActionRow("偏好设置", { onNavigate("preferences") }, leadingIcon = Icons.Outlined.Tune)
            SettingsActionRow("学期与周次", { onNavigate("info") }, leadingIcon = Icons.Outlined.CalendarMonth)
            SettingsActionRow("插件管理", onManage, subtitle = "选择校园功能，管理安装与更新", leadingIcon = Icons.Outlined.Extension, showDivider = false)
        }
        SettingsSection(title = "关于", backdrop = backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            SettingsActionRow("开源许可", { showLicenses = true }, leadingIcon = Icons.Outlined.Description)
            SettingsActionRow("隐私政策", { showPrivacy = true }, leadingIcon = Icons.Outlined.PrivacyTip)
            SettingsActionRow("贡献者", { showContributors = true }, leadingIcon = Icons.Outlined.PeopleOutline)
            SettingsActionRow("意见反馈", {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1006203134&card_type=group&source=qrcode"))) }
                    .onFailure { status = "请安装 QQ，或手动加入反馈群 1006203134" }
            }, leadingIcon = Icons.Outlined.Feedback)
            SettingsActionRow("检查更新", {
                if (!busy) job = scope.launch {
                    status = "正在检查更新…"
                    try {
                        require(marketUrl.isNotBlank()) { "请先在插件管理中配置市场地址" }
                        val connection = HostUpdateClient(context.applicationContext, marketUrl).also { client = it }
                        update = connection.check(); downloaded = null
                        status = if (update == null) "当前已是最新版本" else "发现新版本 ${update!!.versionName}"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { currentCoroutineContext().ensureActive(); status = failure.message }
                    finally { job = null }
                }
            }, leadingIcon = Icons.Outlined.Update, showDivider = false)
        }
        SettingsSection(title = "数据", backdrop = backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            SettingsActionRow("清除所有数据", { showClearData = true }, leadingIcon = Icons.Outlined.DeleteOutline, destructive = true, showDivider = false)
        }
        activationError?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) }
    update?.let { candidate ->
        Text(candidate.releaseNotes.ifBlank { "更新至 ${candidate.versionName}" })
        if (downloaded == null) {
            OutlinedButton(enabled = !busy, onClick = {
                job = scope.launch {
                    try {
                        progress = 0f
                        downloaded = requireNotNull(client).download(candidate) { progress = it }
                        status = "更新包签名和完整性验证通过，可以安装"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { currentCoroutineContext().ensureActive(); status = e.message ?: "下载失败" }
                    finally { progress = null; job = null }
                }
            }) { Text("下载更新（${candidate.size / 1024 / 1024} MB）") }
        } else {
            Button(onClick = {
                runCatching { requireNotNull(client).install(requireNotNull(downloaded)) }
                    .onSuccess { status = "若系统要求授权安装，请授权后返回再次点击安装。" }
                    .onFailure { status = it.message }
            }) { Text("安装已验证更新") }
        }
    }
    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (busy) TextButton(onClick = { client?.cancel(); job?.cancel(); status = "已取消" }) { Text("取消") }
    }
    }

    if (showClearData) AlertDialog(onDismissRequest = { showClearData = false },
        title = { Text("清除所有应用数据？") },
        text = { Text("将删除本机账号、登录凭据、插件、缓存、应用私有下载和设置，并关闭应用。再次打开时需要重新初始化。保存到公共下载目录的文件由系统文件管理器管理。此操作无法撤销。") },
        dismissButton = { TextButton(onClick = { showClearData = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            showClearData = false
            runCatching {
                check(context.getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData()) { "系统未允许清除，请到系统应用设置操作" }
            }.onFailure { status = it.message }
        }) { Text("确认清除") } })

    if (showLicenses) AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("开源许可") },
        text = { androidx.compose.foundation.lazy.LazyColumn {
            items(HostLicenses.license.size) { index ->
                val item = HostLicenses.license[index]
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text("${item.author}\n${item.license}")
                    TextButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.url))) }
                            .onFailure { status = "无法打开项目链接" }
                    }) { Text("项目主页") }
                    item.licenseAsset?.let { path -> TextButton(onClick = {
                        runCatching { listOfNotNull(path, item.noticeAsset).joinToString("\n\n") { asset ->
                            context.assets.open(asset).bufferedReader().use { it.readText() }
                        } }.onSuccess { licenseText = it }.onFailure { status = "无法读取许可文件" }
                    }) { Text("查看许可原文与声明") } }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } })
    if (showContributors) AlertDialog(onDismissRequest = { showContributors = false }, title = { Text("贡献者") },
        text = { androidx.compose.foundation.lazy.LazyColumn {
            items(HostContributors.developers.size) { index ->
                val person = HostContributors.developers[index]
                TextButton(onClick = { person.onclick(context) }) {
                    Column { Text(person.name); Text(person.desc, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showContributors = false }) { Text("关闭") } })
    licenseText?.let { document -> AlertDialog(onDismissRequest = { licenseText = null }, title = { Text("开源许可声明") },
        text = { androidx.compose.foundation.lazy.LazyColumn { item { Text(document) } } },
        confirmButton = { TextButton(onClick = { licenseText = null }) { Text("关闭") } }) }

    if (showPrivacy) {
        val policy = remember { context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showPrivacy = false }, title = { Text("隐私政策") },
            text = { androidx.compose.foundation.lazy.LazyColumn { item { Text(policy) } } },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("关闭") } })
    }
}
