package com.ahu.ahutong.plugin.lost_found

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.data.crawler.model.adwnh.LostFoundPublishRequest
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LostFoundRepositoryTest {
    private lateinit var server: MockWebServer
    @Before fun before() { server = MockWebServer(); server.start() }
    @After fun after() { server.shutdown() }
    private fun repository(guard: () -> Unit = {}, refresh: suspend () -> Boolean = { false }) =
        LostFoundRepository(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(), "fixture-account", guard, refresh, server.url("/"))

    @Test fun retainsPaginationQueryAndObjectResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"ok","object":{"pageNum":2,"pageSize":20,"pages":3,"total":45,"list":[]}}"""))
        val result = repository().getLostFoundList(2, 20, 2)
        assertTrue(result.isSuccess)
        assertEquals(3, result.valueOrNull()!!.data.pages)
        assertEquals("/lostfound/all?pageNo=2&pageSize=20&state=2", server.takeRequest().path)
    }

    @Test fun readsRefreshOnceButPublishNeverRefreshesOrReplays() = runBlocking {
        var refreshed = 0
        val repository = repository(refresh = { refreshed++; true })
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"ok","object":[]}"""))
        assertTrue(repository.getAllCampus().isSuccess)
        assertEquals(1, refreshed)
        server.enqueue(MockResponse().setResponseCode(401))
        val result = repository.publishLostFound(LostFoundPublishRequest(linkman = "fixture", phone = "123", typeid = "kind", campusid = "campus", title = "物品", state = "1"))
        assertTrue(result.errorOrNull() is AhuError.Unauthorized)
        assertEquals(1, refreshed)
        assertEquals(3, server.requestCount)
        server.takeRequest(); server.takeRequest()
        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/lostfound/saveupdate", write.path)
        assertEquals("campus", JsonParser.parseString(write.body.readUtf8()).asJsonObject["campusid"].asString)
    }

    @Test fun lostWriteAcknowledgementIsUncertainAndNotReplayed() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val result = repository().deleteLostFound("a&b")
        assertTrue(result.errorOrNull() is AhuError.Unknown)
        assertEquals(1, server.requestCount)
        val write = server.takeRequest()
        assertEquals("/lostfound/delete", write.path)
        assertEquals("id=a%26b", write.body.readUtf8())
    }

    @Test fun accountChangeStopsRequestBeforeNetwork() = runBlocking {
        val result = repository(guard = { error("account changed") }).getAllCampus()
        assertTrue(result.errorOrNull() is AhuError.Unauthorized)
        assertEquals(0, server.requestCount)
    }
}
