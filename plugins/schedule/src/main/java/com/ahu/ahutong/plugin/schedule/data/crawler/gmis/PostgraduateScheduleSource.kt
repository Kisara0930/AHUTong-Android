package com.ahu.ahutong.plugin.schedule.data.crawler.gmis

import com.ahu.ahutong.plugin.schedule.PluginScope
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.model.AcademicAccountType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.Request

internal data class PortalPage(val url: String, val status: Int, val html: String)

internal object PostgraduateScheduleSource {
    fun forAccount(accountId: String): GmisScheduleClient = GmisScheduleClient(
        openSession = {
            val user = SessionStore.currentUser()
            if (user?.xh != accountId || user.academicAccountType != AcademicAccountType.POSTGRADUATE)
                throw CancellationException("Account changed")
            if (!PluginScope.session.refresh(CampusRealm.POSTGRADUATE)) throw GmisSessionExpiredException()
            PluginScope.session.postgraduateSessionUrl() ?: throw GmisSessionExpiredException()
        },
        query = { address, fields, referer -> withContext(Dispatchers.IO) {
            if (SessionStore.currentUser()?.xh != accountId) throw CancellationException("Account changed")
            val generation = AccountGeneration.current
            val url = address.toHttpUrl()
            val source = referer.toHttpUrl()
            val path = url.encodedPath.replace(Regex("(?i)/\\(S\\([^)]*\\)\\)"), "")
            require(url.scheme == "https" && url.host == "gmis.ahu.edu.cn" && source.host == url.host)
            require(path in setOf("/gmis5/student/pygl/xskbcx", "/gmis5/student/default/bindterm", "/gmis5/student/pygl/py_kbcx_ew"))
            val builder = Request.Builder().url(url).header("Referer", referer).header("X-Requested-With", "XMLHttpRequest")
            if (fields != null) {
                require(path == "/gmis5/student/pygl/py_kbcx_ew" && fields.keys == setOf("kblx", "termcode") && fields["kblx"] == "xs")
                builder.post(FormBody.Builder().apply { fields.forEach { (key,value) -> add(key,value) } }.build())
            }
            PluginScope.session.client(CampusRealm.POSTGRADUATE).newCall(builder.build()).execute().use { response ->
                val html = response.body?.string().orEmpty()
                AccountGeneration.requireCurrent(generation)
                PortalPage(response.request.url.toString(), response.code, html)
            }
        } }
    )
}
