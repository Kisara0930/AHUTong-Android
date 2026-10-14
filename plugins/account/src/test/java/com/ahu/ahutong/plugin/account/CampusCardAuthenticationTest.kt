package com.ahu.ahutong.plugin.account

import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.Request
import kotlin.test.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CampusCardAuthenticationTest {
    @Test fun `one shot CAS ticket is followed once and encoded credential is exchanged without visiting loginTransit`() = runBlocking {
        val requests = mutableListOf<Request>()
        val subject = CampusCardAuthentication({ false }, { 1 }, { true }) { _, request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/oauth/token") -> CampusCardAuthResponse(200, request.url, null, """{"access_token":"test-token"}""")
                request.url.queryParameter("ticket") == "ST-once" -> CampusCardAuthResponse(302, request.url, "https://ycard.ahu.edu.cn/plat/?ticket=cred%252Bvalue")
                else -> CampusCardAuthResponse(302, request.url, "https://ycard.ahu.edu.cn/berserker-auth/cas/redirect/neusoftCas?ticket=ST-once")
            }
        }
        assertEquals("test-token", subject.get())
        assertEquals(3, requests.size)
        assertEquals(1, requests.count { it.url.queryParameter("ticket") == "ST-once" })
        assertTrue(requests.none { it.url.encodedPath == "/plat/" })
        val form = requests.last().body as FormBody
        assertEquals("cred+value", form.value(0))
        assertEquals("cred+value", form.value(1))
    }

    @Test fun `concurrent 401 responses rejecting the same token share one refresh`() = runBlocking {
        val exchanges = AtomicInteger()
        val subject = CampusCardAuthentication({ false }, { 1 }, { true }) { _, request ->
            if (request.url.encodedPath.endsWith("/oauth/token")) {
                delay(20)
                CampusCardAuthResponse(200, request.url, null, """{"access_token":"token-${exchanges.incrementAndGet()}"}""")
            } else CampusCardAuthResponse(302, request.url, "https://ycard.ahu.edu.cn/plat/?ticket=encoded")
        }
        assertEquals("token-1", subject.get())
        val values = (1..20).map { async(Dispatchers.Default) { subject.get("token-1", force = true) } }.awaitAll()
        assertEquals(setOf("token-2"), values.toSet())
        assertEquals(2, exchanges.get())
        assertEquals("token-2", subject.get("token-1", force = true))
        assertEquals(2, exchanges.get())
    }

    @Test fun `late token reply is discarded when the account changes`() = runBlocking {
        val generation = AtomicLong(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val subject = CampusCardAuthentication({ false }, generation::get, { true }) { _, request ->
            if (request.url.encodedPath.endsWith("/oauth/token")) {
                entered.complete(Unit); release.await()
                CampusCardAuthResponse(200, request.url, null, """{"access_token":"old-token"}""")
            } else CampusCardAuthResponse(302, request.url, "https://ycard.ahu.edu.cn/plat/?ticket=encoded")
        }
        val result = async { subject.get() }
        withTimeout(2_000) { entered.await() }
        generation.incrementAndGet(); subject.clear(); release.complete(Unit)
        assertNull(result.await())
        assertNull(subject.snapshot())
    }

    @Test fun `untrusted redirects fail before any request leaves the campus hosts`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val subject = CampusCardAuthentication({ false }, { 1 }, { true }) { _, request ->
            requests += request
            CampusCardAuthResponse(302, request.url, "https://example.invalid/steal?ticket=fixture")
        }
        assertNull(subject.get())
        assertEquals(1, requests.size)
    }

    @Test fun `empty initial session attempts CAS refresh once then retries the bootstrap`() = runBlocking {
        var casReady = false
        var refreshes = 0
        val subject = CampusCardAuthentication({ refreshes++; casReady = true; true }, { 1 }, { true }) { _, request ->
            when {
                request.url.encodedPath.endsWith("/oauth/token") -> CampusCardAuthResponse(200, request.url, null, """{"access_token":"new-token"}""")
                request.url.host == "one.ahu.edu.cn" -> CampusCardAuthResponse(200, request.url, null)
                casReady -> CampusCardAuthResponse(302, request.url, "https://ycard.ahu.edu.cn/plat/?ticket=encoded")
                else -> CampusCardAuthResponse(302, request.url, "https://one.ahu.edu.cn/cas/login?service=https%3A%2F%2Fycard.ahu.edu.cn")
            }
        }
        assertEquals("new-token", subject.get())
        assertEquals(1, refreshes)
    }
}
