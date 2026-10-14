package com.ahu.ahutong.data.notice

import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.plugin.campus_notices.CampusNoticesRuntime
import com.ahu.ahutong.data.session.SessionStore

internal class SessionCampusNoticeFence constructor() : CampusNoticeAccountFence {
    override fun currentAccountId(): String? = SessionStore.currentUser()?.xh?.takeIf { CampusNoticesRuntime.enabled }
    override fun generation(): Long = AccountGeneration.current

    override suspend fun commitIfCurrent(generation: Long, accountId: String, action: () -> Unit): Boolean {
        return synchronized(AccountGeneration) {
            if (AccountGeneration.current != generation || currentAccountId() != accountId) false
            else { action(); true }
        }
    }
}
