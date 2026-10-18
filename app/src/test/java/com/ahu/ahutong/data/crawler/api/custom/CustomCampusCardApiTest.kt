package com.ahu.ahutong.data.crawler.api.custom

import com.ahu.ahutong.data.network.retrofit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * 自定义一卡通协议客户端的响应级契约测试。
 *
 * 这条链路有两点最容易随改版静默坏掉、又不该靠人工回归：余额封套的字段名，以及
 * `getThirdData` 的**传参方式**——文档明确要求走 query string，且 value 里自带的 `&`
 * 必须编码成 `%26`（写成 form body 或漏编码都会被上游当成另一组参数）。这两点在这里钉住。
 *
 * 会话/401 重放依赖 Android 侧的 CredentialVault 与 DataStore，属于 instrumentation 范围，
 * 不在本测试内。
 */
class CustomCampusCardApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CustomCampusCardApi

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 只测协议形状：不装配会话拦截器与阿里 DoH，直接用普通 client。
        api = retrofit(server.url("/").toString(), OkHttpClient())
            .create(CustomCampusCardApi::class.java)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `balance reads elec_accamt and code from the envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":[{"elec_accamt":12345}],"msg":null}"""
            )
        )

        val response = api.getBalance()

        val body = response.body()
        assertEquals(200, body?.code)
        assertEquals(12345L, body?.data?.firstOrNull()?.elecAccAmt)
    }

    @Test
    fun `turnover reuses the existing response shape`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"records":[
                    {"orderId":"o1","tranamt":500,"cardBalance":9000,"typeFrom":"2"}
                ],"total":1,"pages":1}}"""
            )
        )

        val page = api.getTurnover(size = 20, current = 1).body()?.data

        assertEquals("o1", page?.records?.firstOrNull()?.orderId)
        assertEquals(1, page?.total)
    }

    @Test
    fun `third data is a POST whose options are query parameters`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"map":{"data":[{"code":"1","name":"东校区"}]}}"""
            )
        )

        api.getThirdData(
            feeItemId = "221",
            type = "select",
            level = "1",
            campus = "01&东校区"
        ).body()?.close()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("221", recorded.requestUrl?.queryParameter("feeitemid"))
        assertEquals("select", recorded.requestUrl?.queryParameter("type"))
        assertEquals("1", recorded.requestUrl?.queryParameter("level"))
        // 端到端还原：服务端会按 `&` 再切一次，必须已经编码成 %26。
        assertEquals("01&东校区", recorded.requestUrl?.queryParameter("campus"))
        assertTrue(
            recorded.path.orEmpty().contains("campus=01%26"),
            "campus 里的 & 必须编码成 %26：${recorded.path}"
        )
    }

    @Test
    fun `login posts the oauth password grant with the basic authorization header`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"tok","refresh_token":"ref","token_type":"bearer"}"""
            )
        )

        val token = api.login(username = "20210001", password = "secret").execute().body()

        assertEquals("tok", token?.accessToken)
        val recorded = server.takeRequest()
        assertTrue(
            recorded.getHeader("Authorization").orEmpty().startsWith("Basic "),
            "登录必须带 Basic 客户端凭据"
        )
        val form = recorded.body.readUtf8()
        assertTrue(form.contains("grant_type=password"), form)
        assertTrue(form.contains("logintype=sno"), form)
    }

    @Test
    fun `unauthorized responses surface the http code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"code":401,"message":"未登录"}""")
        )

        val response = api.getBalance()

        assertEquals(401, response.code())
    }
}
