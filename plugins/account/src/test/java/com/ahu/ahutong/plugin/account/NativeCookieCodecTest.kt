package com.ahu.ahutong.plugin.account

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import kotlin.test.*

class NativeCookieCodecTest {
    @Test fun `cookie_store JSON stream preserves host only scope path and absolute expiry`() {
        val fixture = """{"raw_cookie":"SID=fixture; HttpOnly; Secure","path":["/student",false],"domain":{"HostOnly":"jw.ahu.edu.cn"},"expires":"SessionEnd"}
{"raw_cookie":"shared=value; Domain=ahu.edu.cn; Path=/; Secure","path":["/",true],"domain":{"Suffix":"ahu.edu.cn"},"expires":{"AtUtc":"2030-01-01T00:00:00Z"}}
"""
        val cookies = NativeCookieCodec.decode(fixture)
        assertEquals(2, cookies.size)
        assertTrue(cookies[0].hostOnly)
        assertTrue(cookies[0].httpOnly)
        assertTrue(cookies[0].secure)
        assertFalse(cookies[0].persistent)
        assertEquals("/student", cookies[0].path)
        assertFalse(cookies[0].matches("https://child.jw.ahu.edu.cn/student".toHttpUrl()))
        assertFalse(cookies[1].hostOnly)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(), cookies[1].expiresAt)
    }

    @Test fun `native and flat exports preserve all relevant cookie attributes`() {
        val cookie = Cookie.Builder().name("session").value("fixture").hostOnlyDomain("ycard.ahu.edu.cn")
            .path("/charge").secure().httpOnly().expiresAt(1_900_000_000_000).build()
        for (encoded in listOf(NativeCookieCodec.encodeNative(listOf(cookie)), NativeCookieCodec.encodeFlat(listOf(cookie)))) {
            val restored = NativeCookieCodec.decode(encoded).single()
            assertEquals(cookie.name, restored.name)
            assertEquals(cookie.value, restored.value)
            assertEquals(cookie.domain, restored.domain)
            assertEquals(cookie.hostOnly, restored.hostOnly)
            assertEquals(cookie.expiresAt, restored.expiresAt)
            assertEquals(cookie.secure, restored.secure)
            assertEquals(cookie.httpOnly, restored.httpOnly)
            assertEquals(cookie.path, restored.path)
        }
    }

    @Test fun `expired cookies remain deletion markers instead of reviving previous values`() {
        val fixture = """{"raw_cookie":"SID=deleted; Max-Age=0","path":["/",true],"domain":{"HostOnly":"jw.ahu.edu.cn"},"expires":{"AtUtc":"2000-01-01T00:00:00Z"}}"""
        assertTrue(NativeCookieCodec.decode(fixture).single().expiresAt < System.currentTimeMillis())
    }

    @Test fun `unrelated and inconsistent domains are rejected before mutation`() {
        assertFails { NativeCookieCodec.decode("""[{"name":"SID","value":"fixture","domain":"ahu.edu.cn.evil.example"}]""") }
        assertFails { NativeCookieCodec.decode("""{"raw_cookie":"SID=x; Domain=ahu.edu.cn","path":["/",true],"domain":{"HostOnly":"jw.ahu.edu.cn"},"expires":"SessionEnd"}""") }
    }
}
