package com.ahu.ahutong.plugin.networkrecharge

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.model.LoginOutcome
import com.ahu.ahutong.data.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.test.*

class YcardGatewayTest {
    @Test fun `401 refreshes the rejected token and replays the request once`() = runBlocking {
        val session = SessionFixture(mutableListOf(401, 200))
        val response = YcardGateway(session).authorizedCall { pay(FormBody.Builder().add("fixture", "no-payment").build()) }
        assertTrue(response.isSuccessful)
        response.body()?.close()
        assertEquals<List<String?>>(listOf("first"), session.rejected)
        assertEquals(2, session.calls)
        assertEquals(listOf(true, true), session.oneShotBodies)
    }

    @Test fun `server errors and transport errors never replay a payment`() = runBlocking {
        val failure = SessionFixture(mutableListOf(500))
        val response = YcardGateway(failure).authorizedCall { pay(FormBody.Builder().build()) }
        response.errorBody()?.close()
        assertEquals(500, response.code())
        assertEquals(1, failure.calls)
        assertTrue(failure.rejected.isEmpty())
        val timeout = SessionFixture(mutableListOf()).apply { transportFailure = true }
        assertFailsWith<IOException> { YcardGateway(timeout).authorizedCall { pay(FormBody.Builder().build()) } }
        assertEquals(1, timeout.calls)
        assertTrue(timeout.rejected.isEmpty())
    }

    @Test fun `a second 401 is returned without another retry`() = runBlocking {
        val session = SessionFixture(mutableListOf(401, 401))
        val response = YcardGateway(session).authorizedCall { pay(FormBody.Builder().build()) }
        response.errorBody()?.close()
        assertEquals(401, response.code())
        assertEquals(2, session.calls)
        assertEquals(1, session.rejected.size)
    }

    @Test fun `refresh uses the token actually sent when another request refreshed before dispatch`() = runBlocking {
        val session = SessionFixture(mutableListOf(401, 200)).apply { wireToken = "concurrently-refreshed" }
        val response = YcardGateway(session).authorizedCall { pay(FormBody.Builder().build()) }
        response.body()?.close()
        assertEquals<List<String?>>(listOf("concurrently-refreshed"), session.rejected)
    }

    @Test fun `late response after account switch cannot refresh or replay under the new account`() = runBlocking {
        val session = SessionFixture(mutableListOf(401)).apply { switchAccountOnResponse = true }
        assertFailsWith<IllegalStateException> { YcardGateway(session).authorizedCall { pay(FormBody.Builder().build()) } }
        assertEquals(1, session.calls)
        assertTrue(session.rejected.isEmpty())
    }

    private class SessionFixture(private val statuses: MutableList<Int>) : CampusSessionService {
        override val state = MutableStateFlow(AhuSessionState.Status.Authenticated)
        var calls = 0
        var token = "first"
        var transportFailure = false
        var switchAccountOnResponse = false
        var wireToken: String? = null
        val rejected = mutableListOf<String?>()
        val oneShotBodies = mutableListOf<Boolean>()
        override fun client(realm: CampusRealm): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            oneShotBodies += chain.request().body?.isOneShot() == true
            if (transportFailure) throw IOException("fixture transport failure")
            if (switchAccountOnResponse) AccountGeneration.advance()
            val request = chain.request().newBuilder().apply { wireToken?.let { header("Synjones-Auth", "bearer $it") } }.build()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(statuses.removeAt(0)).message("offline fixture").body("{}".toResponseBody()).build()
        }.build()
        override suspend fun campusCardToken() = token
        override suspend fun refreshCampusCardToken(rejectedToken: String?): String {
            rejected += rejectedToken
            token = "second"
            return token
        }
        override suspend fun refresh(realm: CampusRealm): Boolean = error("Must use rejected-token refresh")
        override suspend fun signIn(username: String, password: String): AhuResult<LoginOutcome> = error("No credentials in this fixture")
        override suspend fun completeWebVerification() = Unit
        override suspend fun signOut() = Unit
        override suspend fun ensureFresh(observedGeneration: Long) = false
        override suspend fun refreshCas(serviceUrl: String) = false
        override suspend fun importWebCookies(cookies: String, observedGeneration: Long): AhuResult<Unit> = error("No cookies in this fixture")
        override fun nativeCookies(realm: CampusRealm, observedGeneration: Long): String = error("No native transport in this fixture")
        override fun acceptNativeResult(realm: CampusRealm, response: String, observedGeneration: Long): String = error("No native transport in this fixture")
        override fun postgraduateSessionUrl(): String? = null
    }
}
