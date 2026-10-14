package com.ahu.ahutong.plugin.account

import com.ahu.ahutong.data.session.AccountGeneration
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** The generation check and mutation use the same monitor as account switching. */
internal class GenerationCookieJar(
    private val delegate: CookieJar,
    private val expected: Long,
    private val monitor: Any = AccountGeneration,
    private val current: () -> Long = { AccountGeneration.current }
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(monitor) {
        if (current() == expected) delegate.saveFromResponse(url, cookies)
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(monitor) {
        if (current() == expected) delegate.loadForRequest(url) else emptyList()
    }
}
