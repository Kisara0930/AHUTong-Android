package com.ahu.ahutong.data.mail

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.util.UUID

/** One-use navigation capability; the intent never contains a sid or cookies. */
object StudentMailWebLease {
    const val KEY_LEASE = "mail_lease"
    const val METHOD_OPEN = "open"
    const val METHOD_CHECK = "check"
    const val METHOD_CLOSE = "close"
    private var active: Pair<String, MailSession>? = null

    @Synchronized fun open(session: MailSession): String {
        session.assertCurrent()
        return UUID.randomUUID().toString().also { active = it to session }
    }

    @Synchronized fun read(key: String?, includeSession: Boolean): Bundle {
        val session = active?.takeIf { it.first == key }?.second ?: return Bundle()
        return try {
            session.assertCurrent()
            Bundle().apply {
                putBoolean("valid", true)
                if (includeSession) {
                    putString("sid", session.sid)
                    putStringArrayList("cookies", ArrayList(StudentMailSession.mailboxCookies(session).map { it.toString() }))
                }
            }
        } catch (_: Exception) {
            active = null
            Bundle()
        }
    }

    @Synchronized fun clearAll() { active = null }

    @Synchronized fun close(key: String?) {
        if (active?.first == key) active = null
    }
}
