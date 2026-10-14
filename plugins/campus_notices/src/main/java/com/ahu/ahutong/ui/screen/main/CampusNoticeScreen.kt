package com.ahu.ahutong.ui.screen.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ahu.ahutong.plugin.campus_notices.CampusNoticesRuntime
import com.ahu.ahutong.data.notice.CampusNotice
import com.ahu.ahutong.data.notice.CampusNoticeRepository
import com.ahu.ahutong.data.notice.CampusNoticeUrlPolicy
import com.ahu.ahutong.data.notice.PostgraduateNoticeParser
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.ui.components.AppCard
import com.ahu.ahutong.ui.components.AppModalBottomSheet
import com.ahu.ahutong.ui.components.AppPageScaffold
import com.ahu.ahutong.ui.components.AppStateCard
import com.ahu.ahutong.ui.components.AppToggle
import com.ahu.ahutong.ui.components.TrailingAction
import com.ahu.ahutong.ui.shape.SmoothRoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusNoticeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val observedAccountId by SessionStore.accountIdUpdates().collectAsState()
    val accountId = observedAccountId
    val graduate = CampusNoticesRuntime.isGraduate()
    val stored by CampusNoticeRepository.snapshot.collectAsState()
    val syncingAccountId by CampusNoticeRepository.syncingAccountId.collectAsState()
    val snapshot = stored?.takeIf { it.accountId == accountId }
    val isRefreshing = !accountId.isNullOrBlank() && syncingAccountId == accountId
    val notices = remember(snapshot?.notices) {
        snapshot?.notices.orEmpty().sortedWith(
            compareByDescending<CampusNotice> { it.publishedOn }
                .thenByDescending { it.discoveredAtMillis }
        )
    }
    var showSettings by remember { mutableStateOf(false) }
    var homeVisible by remember { mutableStateOf(CampusNoticesRuntime.homeCards()?.isVisible("campus_notices") == true) }

    LaunchedEffect(accountId) {
        if (!accountId.isNullOrBlank()) {
            CampusNoticeRepository.open(accountId)
            CampusNoticeRepository.requestSyncIfDue()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && !accountId.isNullOrBlank()) {
            CampusNoticeRepository.setNotificationsEnabled(accountId, true)
        }
    }

    fun openArticle(notice: CampusNotice) {
        if (accountId.isNullOrBlank() ||
            !CampusNoticeUrlPolicy.isTrustedArticle(notice.sourceId, notice.originalUrl)
        ) return
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.originalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) CampusNoticeRepository.markRead(accountId, notice.articleId)
        else Toast.makeText(context, "无法打开公告链接", Toast.LENGTH_SHORT).show()
    }

    fun openOfficialSite() {
        val url = if (graduate) "https://graschool.ahu.edu.cn/9577/list.htm"
        else "https://www.ahu.edu.cn/15046/list.htm"
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) Toast.makeText(context, "无法打开官方网站", Toast.LENGTH_SHORT).show()
    }

    AppPageScaffold(
        title = "校园通知",
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        actions = listOf(
            TrailingAction(Icons.Outlined.OpenInBrowser, "在浏览器查看") { openOfficialSite() },
            TrailingAction(Icons.Outlined.Settings, "校园通知设置") { showSettings = true }
        ),
        freeContent = {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { CampusNoticeRepository.requestSyncIfDue(force = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (notices.isEmpty()) {
                        item(key = "state") {
                            val error = snapshot?.sourceStatuses?.values?.any { it.lastError != null } == true
                            when {
                                accountId.isNullOrBlank() -> AppStateCard.Empty("请先登录以查看公告")
                                isRefreshing || snapshot == null -> AppStateCard.Loading()
                                error -> AppStateCard.Error(message = "下拉刷新重试", title = "公告暂时无法加载")
                                else -> AppStateCard.Empty("暂无公告")
                            }
                        }
                    } else {
                        items(notices, key = { "${it.sourceId}:${it.articleId}" }) { notice ->
                            CampusNoticeRow(notice = notice, onClick = { openArticle(notice) })
                        }
                    }
                }
            }
        }
    )

    if (showSettings) {
        AppModalBottomSheet(
            title = "校园通知设置",
            onDismissRequest = { showSettings = false }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("新公告通知", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "打开应用检测到新公告时提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AppToggle(
                        checked = snapshot?.notificationsEnabled == true,
                        onCheckedChange = { enabled ->
                            if (accountId.isNullOrBlank()) return@AppToggle
                            if (!enabled) {
                                CampusNoticeRepository.setNotificationsEnabled(accountId, false)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                CampusNoticeRepository.setNotificationsEnabled(accountId, true)
                            }
                        },
                        enabled = !accountId.isNullOrBlank(),
                        contentDescription = "新公告通知"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("在首页显示", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "在首页小工具中显示校园通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AppToggle(
                        checked = homeVisible,
                        onCheckedChange = { enabled ->
                            if (CampusNoticesRuntime.homeCards()?.setVisible("campus_notices", enabled) == true) homeVisible = enabled
                        },
                        contentDescription = "在首页显示校园通知"
                    )
                }

                if ((snapshot?.unreadCount ?: 0) > 0 && !accountId.isNullOrBlank()) {
                    TextButton(onClick = { CampusNoticeRepository.markRead(accountId, null) }) {
                        Text("全部标为已读")
                    }
                }
                Text(
                    "未读公告超过 7 天后自动标为已读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

}

@Composable
private fun CampusNoticeRow(notice: CampusNotice, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SmoothRoundedCornerShape(20.dp),
        onClick = onClick
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = notice.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (notice.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!notice.read) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "未读",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Text(
                "${if (notice.sourceId == PostgraduateNoticeParser.SOURCE_ID) "研究生院" else "安徽大学"} · ${notice.publishedOn}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
