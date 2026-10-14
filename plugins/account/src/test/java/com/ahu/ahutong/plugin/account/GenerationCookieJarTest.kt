package com.ahu.ahutong.plugin.account

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*
import com.ahu.ahutong.data.session.AccountGeneration

class GenerationCookieJarTest {
    @Test fun `new account generation is published only after previous identity cleanup`() {
        val before = AccountGeneration.current
        var cleared = false
        val after = AccountGeneration.advanceAndCommit {
            assertEquals(before, AccountGeneration.current)
            cleared = true
        }
        assertTrue(cleared)
        assertEquals(before + 1, after)
        var staleCommitRan = false
        assertFailsWith<IllegalStateException> { AccountGeneration.withCurrent(before) { staleCommitRan = true } }
        assertFalse(staleCommitRan)
    }
    @Test fun `old replies cannot repopulate cookies after account switch`() {
        val lock = Any()
        var generation = 1L
        val saved = mutableListOf<Cookie>()
        val delegate = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { saved.addAll(cookies) }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = saved.toList()
        }
        val jar = GenerationCookieJar(delegate, 1, lock) { generation }
        val url = "https://jw.ahu.edu.cn/student/home".toHttpUrl()
        val cookie = Cookie.Builder().name("SID").value("old-account").hostOnlyDomain(url.host).build()
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals(1, saved.size)
        synchronized(lock) { generation = 2; saved.clear() }
        jar.saveFromResponse(url, listOf(cookie))
        assertTrue(saved.isEmpty())
        assertTrue(jar.loadForRequest(url).isEmpty())
    }
}
