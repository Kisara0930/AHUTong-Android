package com.ahu.ahutong.feature.circle

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircleApiTest {
    /** Intercept at the network boundary; tests never contact the real BBS. */
    private fun client(captured: MutableList<Request>) = OkHttpClient.Builder()
        .addNetworkInterceptor { error("A real network connection must never be made") }
        .addInterceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("fixture unavailable").body("{}".toResponseBody()).build()
        }.build()

    @Test fun `feed detail and comments use only read endpoints without campus credentials`() = runBlocking {
        val captured = mutableListOf<Request>()
        val api = CircleApi(client(captured))
        assertFailsWith<CircleApiException> { api.fetchTopics(1) }
        assertFailsWith<CircleApiException> { api.fetchTopicDetail("topic-1") }
        assertFailsWith<CircleApiException> { api.fetchComments("topic-1") }
        assertEquals(listOf("/api/client/topics", "/api/client/topics/read_only/topic-1", "/api/client/comments"),
            captured.map { it.url.encodedPath })
        captured.forEach {
            assertEquals("GET", it.method)
            assertEquals("api.zxs-bbs.cn", it.url.host)
            assertEquals("7", it.header("Tenant"))
            assertEquals("1", it.header("xweb_xhr"))
            assertNull(it.header("Authorization"))
            assertNull(it.header("Cookie"))
        }
    }

    @Test fun `remote topic identifiers cannot change paths or add query parameters`() = runBlocking {
        val captured = mutableListOf<Request>()
        val api = CircleApi(client(captured))
        val id = "topic/other?admin=true&write=1"
        assertFailsWith<CircleApiException> { api.fetchTopicDetail(id) }
        assertEquals(id, captured.single().url.pathSegments.last())
        assertNull(captured.single().url.query)
        captured.clear()
        assertFailsWith<CircleApiException> { api.fetchComments(id) }
        assertEquals(id, captured.single().url.queryParameter("topic_id"))
        assertNull(captured.single().url.queryParameter("write"))
    }

    @Test fun `invalid pagination fails before invoking HTTP`() = runBlocking {
        val captured = mutableListOf<Request>()
        assertFailsWith<IllegalArgumentException> { CircleApi(client(captured)).fetchTopics(0) }
        assertTrue(captured.isEmpty())
    }
}
