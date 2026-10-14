package com.ahu.ahutong.plugin.lost_found

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.crawler.model.adwnh.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LostFoundRepository(
    private val client: OkHttpClient,
    val accountId: String,
    private val guard: () -> Unit,
    private val refresh: suspend () -> Boolean = { false },
    private val baseUrl: HttpUrl = "https://adwmh.ahu.edu.cn/".toHttpUrl()
) {
    private val gson = Gson()
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    fun close() { closed = true; calls.forEach(Call::cancel); calls.clear() }
    private fun assertCurrent() { check(!closed); guard() }
    suspend fun getAllCampus(): AhuResult<AllCampus> = read("lostfound/campus/all", emptyMap(), AllCampus::class.java)
    suspend fun getAllLostFoundType(): AhuResult<AllLostFoundType> = read("lostfound/type/all", emptyMap(), AllLostFoundType::class.java)
    suspend fun getLostFoundList(pageNo: Int, pageSize: Int, state: Int): AhuResult<LostFoundResponse> {
        require(pageNo >= 1 && pageSize in 1..100 && state in 1..2)
        return read("lostfound/all", mapOf("pageNo" to "$pageNo", "pageSize" to "$pageSize", "state" to "$state"), LostFoundResponse::class.java)
    }

    private suspend fun <T> read(path: String, query: Map<String, String>, type: Class<T>): AhuResult<T> {
        suspend fun once(): AhuResult<T> = request(path, query, null).let { response ->
            when (response) {
                is AhuResult.Failure -> response
                is AhuResult.Success -> try {
                    require(response.value.has("object") && !response.value["object"].isJsonNull)
                    AhuResult.Success(checkNotNull(gson.fromJson(response.value, type)))
                } catch (_: Exception) { AhuResult.Failure(AhuError.ProtocolChanged("失物招领响应缺少有效数据")) }
            }
        }
        val first = once()
        if (!closed && first.errorOrNull() is AhuError.Unauthorized && refresh()) { assertCurrent(); return once() }
        return first
    }

    suspend fun publishLostFound(request: LostFoundPublishRequest): AhuResult<Any> = mutation(
        "lostfound/saveupdate", gson.toJson(request).toRequestBody("application/json; charset=UTF-8".toMediaType())
    )
    suspend fun deleteLostFound(id: String): AhuResult<Any> {
        require(id.isNotBlank())
        return mutation("lostfound/delete", FormBody.Builder().add("id", id).build())
    }
    private suspend fun mutation(path: String, payload: RequestBody): AhuResult<Any> = when (val response = request(path, emptyMap(), payload)) {
        is AhuResult.Failure -> response
        is AhuResult.Success -> {
            val value = response.value.get("data")
            if (value == null || value.isJsonNull) AhuResult.Failure(AhuError.ProtocolChanged("操作结果尚未确认，请先刷新我的帖子，避免重复操作"))
            else AhuResult.Success(value)
        }
    }

    private suspend fun request(path: String, query: Map<String, String>, body: RequestBody?): AhuResult<JsonObject> = withContext(Dispatchers.IO) {
        try {
            assertCurrent()
            val url = baseUrl.newBuilder().addPathSegments(path).apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            if (body != null) builder.post(object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                override fun isOneShot() = true
            })
            val transport = if (body == null) client else client.newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
            val call = transport.newCall(builder.build())
            calls.add(call)
            try { call.await().use { response ->
                assertCurrent()
                if (response.code == 401 || response.code == 403 || response.code in 300..399) {
                    return@withContext AhuResult.Failure(AhuError.Unauthorized("智慧安大登录已失效，请重新登录"))
                }
                if (!response.isSuccessful) return@withContext AhuResult.Failure(AhuError.Server(response.code, "失物招领服务暂时不可用"))
                val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                assertCurrent()
                val code = root.get("code")?.asInt ?: return@withContext AhuResult.Failure(AhuError.ProtocolChanged("失物招领响应缺少状态码"))
                if (code == 401) AhuResult.Failure(AhuError.Unauthorized("智慧安大登录已失效，请重新登录"))
                // Legacy read adapters accepted the typed `object` payload. Some
                // ADWMH controllers use a different success code than mutation APIs.
                else if (code == 0 || (body == null && root.has("object") && !root["object"].isJsonNull)) AhuResult.Success(root)
                else AhuResult.Failure(AhuError.Server(code, root.get("msg")?.asString.orEmpty().take(300)))
            } } finally { calls.remove(call) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val error = when {
                runCatching { assertCurrent() }.isFailure -> AhuError.Unauthorized("账号已切换或组件已停用，请重新进入")
                body != null && failure is IOException -> AhuError.Unknown("操作结果尚未确认，请先刷新我的帖子，避免重复操作")
                failure is SocketTimeoutException -> AhuError.Timeout
                failure is IOException -> AhuError.Network
                else -> AhuError.ProtocolChanged("失物招领返回了无法识别的数据")
            }
            AhuResult.Failure(error)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, discarded, _ -> discarded.close() } }
    })
}
