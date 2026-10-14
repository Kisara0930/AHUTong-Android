package com.ahu.ahutong.notification

import android.content.Context
import android.net.Uri
import com.ahu.ahutong.data.notice.CampusNotice
import com.ahu.ahutong.data.notice.CampusNoticeUrlPolicy
import com.ahu.ahutong.data.notice.PostgraduateNoticeParser
import com.ahu.ahutong.plugin.campus_notices.CampusNoticesRuntime

/** Android notification components belong to the host; article identity remains plugin-owned. */
object CampusNoticeNotifier {
    fun post(context: Context, accountId: String, notices: List<CampusNotice>) {
        if (!CampusNoticesRuntime.enabled) return
        val scheduler = CampusNoticesRuntime.notifications() ?: return
        notices.filter { CampusNoticeUrlPolicy.isTrustedArticle(it.sourceId, it.originalUrl) }.forEach { notice ->
            val route = Uri.Builder().path("article").appendQueryParameter("account", accountId)
                .appendQueryParameter("id", notice.articleId).appendQueryParameter("url", notice.originalUrl).build().toString()
            scheduler.notify("campus_notices", if (notice.sourceId == PostgraduateNoticeParser.SOURCE_ID)
                "校园通知 · 研究生院" else "校园通知 · 安徽大学", notice.title, route)
        }
    }
    fun cancelOtherAccounts(context: Context, accountId: String) {
        // Account-generation changes cancel all previous-account notifications in plugin lifecycle.
    }
    fun cancelAll(context: Context) { CampusNoticesRuntime.notifications()?.cancel("campus_notices") }
}
