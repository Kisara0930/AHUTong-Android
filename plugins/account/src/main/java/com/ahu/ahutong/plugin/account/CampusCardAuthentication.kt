package com.ahu.ahutong.plugin.account

import com.ahu.ahutong.data.network.AhuHttp
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

internal data class CampusCardAuthResponse(val code: Int, val url: HttpUrl, val location: String?, val body: String = "")

/** Injectable transport and session clock keep CAS/token regressions entirely offline. */
internal class CampusCardAuthentication(
    private val refreshCas: suspend (String) -> Boolean,
    private val currentGeneration: () -> Long = { AccountGeneration.current },
    private val loggedIn: () -> Boolean = { SessionStore.isLoggedIn() },
    private val request: suspend (Long, Request) -> CampusCardAuthResponse = { epoch, request -> execute(epoch, request) }
) {
    private data class Token(val value: String, val epoch: Long)
    private val mutex = Mutex()
    private val revisions = AtomicLong()
    @Volatile private var cached: Token? = null
    @Volatile private var lastAttemptEpoch = -1L

    fun snapshot(): String? = cached?.takeIf { it.epoch == currentGeneration() }?.value
    fun clear() { revisions.incrementAndGet(); cached = null; lastAttemptEpoch = -1 }

    suspend fun get(rejected: String? = null, force: Boolean = false): String? {
        val observedRevision = revisions.get()
        val epoch = currentGeneration()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (epoch != currentGeneration() || !loggedIn()) return@withLock null
                val current = cached?.takeIf { it.epoch == epoch }
                if (current != null && (!force || (rejected != null && current.value != rejected))) return@withLock current.value
                // Reuse success or failure from a refresh that completed while waiting.
                if (revisions.get() != observedRevision && lastAttemptEpoch == epoch) return@withLock current?.value
                if (force) cached = null
                try {
                    val first = fetch(epoch)
                    val value = if (first.second != null && refreshCas(first.second!!)) {
                        checkCurrent(epoch)
                        fetch(epoch).first
                    } else first.first
                    checkCurrent(epoch)
                    value?.takeIf { it.isNotBlank() }?.also {
                        // Old-generation snapshots are invisible even if clear races this assignment.
                        cached = Token(it, epoch)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                finally { lastAttemptEpoch = epoch; revisions.incrementAndGet() }
            }
        }
    }

    private fun checkCurrent(epoch: Long) { check(epoch == currentGeneration()) { "账号已切换" } }

    private suspend fun fetch(epoch: Long): Pair<String?, String?> {
        var url = ENTRY.toHttpUrl().newBuilder().addQueryParameter("targetUrl", TARGET).build()
        var cas: String? = null
        var credential: String? = null
        val visited = mutableSetOf<String>()
        for (attempt in 0 until 12) {
            currentCoroutineContext().ensureActive()
            checkCurrent(epoch)
            validateUrl(url)
            credential = credential(url)
            // Encoded campus credentials are exchanged directly; never GET loginTransit with them.
            if (credential != null) break
            check(visited.add(url.toString())) { "校园卡认证重定向循环" }
            if (isCasLogin(url)) cas = url.toString()
            val response = request(epoch, Request.Builder().url(url).build())
            checkCurrent(epoch)
            validateUrl(response.url)
            if (response.code !in 300..399) return null to cas
            url = requireNotNull(response.location?.let(response.url::resolve)) { "认证重定向缺少目标" }
        }
        // The final redirect can contain the credential after the last allowed HTTP request.
        validateUrl(url)
        val raw = credential ?: credential(url) ?: return null to cas
        val decoded = URLDecoder.decode(URLDecoder.decode(raw, "UTF-8"), "UTF-8")
        val body = FormBody.Builder().add("username", decoded).add("password", decoded)
            .add("grant_type", "password").add("scope", "all").add("loginFrom", "h5")
            .add("logintype", "sso").add("device_token", "h5").add("synAccessSource", "h5").build()
        checkCurrent(epoch)
        val response = request(epoch, Request.Builder().url(TOKEN)
            .header("Authorization", BASIC).post(body).build())
        checkCurrent(epoch)
        validateUrl(response.url)
        val value = if (response.code in 200..299) JsonParser.parseString(response.body).asJsonObject
            .get("access_token")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } else null
        return value to null
    }

    private fun validateUrl(url: HttpUrl) {
        require(url.scheme == "https" && url.host in setOf("ycard.ahu.edu.cn", "one.ahu.edu.cn")) {
            "校园卡认证返回了不受信任的地址"
        }
    }

    private fun credential(url: HttpUrl): String? {
        if (url.host != "ycard.ahu.edu.cn") return null
        val raw = Regex("[?&]ticket=([^&]+)").find(url.toString())?.groupValues?.getOrNull(1) ?: return null
        val decoded = URLDecoder.decode(raw, "UTF-8")
        return raw.takeUnless { decoded.startsWith("ST-", true) || decoded.startsWith("PT-", true) }
    }

    private fun isCasLogin(url: HttpUrl) = url.host == "one.ahu.edu.cn" && url.encodedPath.substringBefore(';') == "/cas/login"

    companion object {
        private const val ENTRY = "https://ycard.ahu.edu.cn/berserker-auth/cas/redirect/neusoftCas"
        private const val TARGET = "https://ycard.ahu.edu.cn/plat/?name=loginTransit"
        private const val TOKEN = "https://ycard.ahu.edu.cn/berserker-auth/oauth/token"
        private const val BASIC = "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"
        private fun execute(epoch: Long, request: Request): CampusCardAuthResponse {
            val client = AhuHttp.plain(connectTimeoutSeconds = 10, readTimeoutSeconds = 10,
                callTimeoutSeconds = 45, retryOnConnectionFailure = false, followRedirects = false, followSslRedirects = false)
                .addInterceptor(noAutomaticFormReplay)
                .cookieJar(CookieManager.forGeneration(epoch)).build()
            return client.newCall(request).execute().use {
                AccountGeneration.requireCurrent(epoch)
                CampusCardAuthResponse(it.code, it.request.url, it.header("Location"),
                    if (request.url.toString() == TOKEN) it.body?.string().orEmpty() else "")
            }
        }
    }
}
