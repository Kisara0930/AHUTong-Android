package com.ahu.ahutong.plugin.account

import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.model.LoginOutcome
import com.ahu.ahutong.data.model.User
import com.ahu.ahutong.data.network.AhuHttp
import com.ahu.ahutong.data.session.*
import com.ahu.ahutong.data.security.SecureStorage
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** All campus authentication lives in the account package; no optional business code. */
class AccountSession : CampusSessionService {
    override val state get() = AhuSessionState.status
    private val loginMutex = Mutex()
    private var pendingUser: User? = null
    private var pendingPassword: String? = null
    private var pendingGeneration = -1L
    private var graduateUrl: String? = null
    @Volatile private var refreshRevision = 0L
    @Volatile private var wisdomRefreshRevision = 0L
    private var lastRefreshSucceeded = false
    private var lastWisdomRefreshSucceeded = false
    private val cardAuthentication = CampusCardAuthentication(::refreshCas)
    private val clients = mutableMapOf<CampusRealm, OkHttpClient>()
    private val transport by lazy { AhuHttp.plain(callTimeoutSeconds = 45, retryOnConnectionFailure = false).build() }
    private val loginClient get() = transport.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .apply { interceptors().add(0, noAutomaticFormReplay) }
        .cookieJar(CookieManager.forGeneration(AccountGeneration.current)).build()

    init {
        // Rust-only legacy sessions may predate the encrypted OkHttp cookie jar.
        if (CookieManager.cookieJar.allCookies.isEmpty()) {
            val epoch = AccountGeneration.current
            runCatching { importCookies(SessionStore.rustCookies(), null, epoch) }
        }
    }

    override fun client(realm: CampusRealm): OkHttpClient = synchronized(clients) {
        clients.getOrPut(realm) {
            val epoch = AccountGeneration.current
            loginClient.newBuilder().followRedirects(true).followSslRedirects(true)
                .cookieJar(CookieManager.forGeneration(epoch)).addInterceptor { chain ->
                val request = chain.request()
                require(request.url.scheme == "https" && permitted(realm, request.url.host)) {
                    "该认证客户端不能访问此地址"
                }
                val generation = epoch
                AccountGeneration.requireCurrent(generation)
                val outgoing = request.newBuilder().header("User-Agent", BROWSER_USER_AGENT).apply {
                    if (realm == CampusRealm.CAMPUS_CARD && request.url.host == "ycard.ahu.edu.cn") {
                        cardAuthentication.snapshot()?.let { header("Synjones-Auth", "bearer $it") }
                    }
                }.build()
                val response = chain.proceed(outgoing)
                if (generation != AccountGeneration.current) {
                    response.close()
                    throw java.io.IOException("账号已切换")
                }
                response
            }.addNetworkInterceptor { chain ->
                // Application interceptors run only once across redirects. Validate every hop
                // before the nonstandard Synjones-Auth header could leave its campus realm.
                val target = chain.request().url
                require(target.scheme == "https" && permitted(realm, target.host)) { "认证请求跳转地址无效" }
                AccountGeneration.requireCurrent(epoch)
                chain.proceed(chain.request())
            }.build()
        }
    }

    override suspend fun signIn(username: String, password: String): AhuResult<LoginOutcome> =
        withContext(Dispatchers.IO) {
            loginMutex.withLock {
                SavedAccounts.rememberCurrent()
                val epoch = clearSession()
                authenticate(username, password).also { result ->
                    AccountGeneration.requireCurrent(epoch)
                    when (val outcome = result.valueOrNull()) {
                        is LoginOutcome.Success -> persist(outcome.user, password, epoch)
                        is LoginOutcome.JwxtWebVerificationRequired -> {
                            pendingUser = outcome.user
                            pendingPassword = password
                            pendingGeneration = epoch
                        }
                        null -> Unit
                    }
                }
            }
        }

    private suspend fun authenticate(username: String, password: String): AhuResult<LoginOutcome> = try {
        withTimeout(120_000) {
            val portal = AcademicPortalLogin(request = AcademicPortalHttp::request)
            AcademicLoginFlow(
                wisdom = ::wisdomLogin,
                undergraduate = { account, secret, user ->
                    portal.login(AcademicPortalLogin.UNDERGRADUATE_ENTRY, account, secret, user)
                },
                postgraduate = { account, secret, user ->
                    portal.login(AcademicPortalLogin.GMIS_ENTRY, account, secret, user)
                        .also { if (it.status == PortalLoginStatus.SUCCESS) graduateUrl = it.sessionPageUrl }
                }
            ).login(username, password)
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { AhuResult.Failure(AhuError.ProtocolChanged(e.message ?: "登录暂不可用")) }

    private suspend fun wisdomLogin(account: String, password: String): AhuResult<User> =
        WisdomLoginFlow(
            fetchCaptcha = {
                loginClient.newCall(Request.Builder().url("https://adwmh.ahu.edu.cn/remind/authcode").build())
                    .execute().use { check(it.isSuccessful); requireNotNull(it.body).bytes() }
            },
            solveCaptcha = { bytes ->
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("captcha", "img.jpg", bytes.toRequestBody("image/jpg".toMediaType())).build()
                loginClient.newCall(Request.Builder().url("https://openahu.org/ocr/captcha").post(body).build())
                    .execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()).getString("result") }
            },
            submitLogin = { user, secret, captcha ->
                val body = FormBody.Builder().add("username", user).add("pwd", secret)
                    .add("flag", "0").add("imgcode", captcha).build()
                loginClient.newCall(Request.Builder().url("https://adwmh.ahu.edu.cn/user/login")
                    .header("X-Requested-With", "XMLHttpRequest").post(body).build())
                    .execute().use { check(it.isSuccessful); requireNotNull(it.body).string() }
            }
        ).login(account, password)

    private fun persist(user: User, password: String, epoch: Long) = AccountGeneration.withCurrent(epoch) {
        SecureCredentialVault.saveWisdomPassword(password)
        SessionStore.persistCurrentUser(user)
        graduateUrl?.let { SecureStorage.putString("account.gmis_session_url", it) }
        SavedAccounts.save(user, password)
        saveCookies()
        pendingUser = null
        pendingPassword = null
        pendingGeneration = -1
        AhuSessionState.markAuthenticated()
    }

    override suspend fun importWebCookies(cookies: String, observedGeneration: Long): AhuResult<Unit> = withContext(Dispatchers.IO) {
        loginMutex.withLock {
        try {
            check(pendingGeneration == observedGeneration && pendingUser != null) { "登录验证已失效" }
            importCookies(cookies, null, observedGeneration)
            val page = AcademicPortalHttp.request(AcademicPortalLogin.UNDERGRADUATE_ENTRY, null)
            check(page.status in 200..299 && page.url.toHttpUrl().host == "jw.ahu.edu.cn" &&
                page.url.toHttpUrl().encodedPath.trimEnd('/') == "/student/home") { "教务验证会话未生效" }
            AccountGeneration.withCurrent(observedGeneration) { saveCookies() }
            AhuResult.Success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AhuResult.Failure(AhuError.ProtocolChanged(e.message ?: "验证失败")) }
        }
    }

    override suspend fun completeWebVerification() = loginMutex.withLock {
        val user = requireNotNull(pendingUser) { "登录请求已失效" }
        persist(user, requireNotNull(pendingPassword), pendingGeneration)
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) { loginMutex.withLock { clearSession(); Unit } }

    private fun clearSession(): Long = AccountGeneration.advanceAndCommit {
        synchronized(clients) { clients.clear() }
        cardAuthentication.clear()
        CookieManager.cookieJar.clear()
        SessionStore.saveEvalToken("")
        SessionStore.clearPersistedCurrentUser()
        SessionStore.saveRustCookies("")
        SecureCredentialVault.clearWisdomPassword()
        pendingUser = null
        pendingPassword = null
        pendingGeneration = -1
        graduateUrl = null
        SecureStorage.remove("account.gmis_session_url")
        AhuSessionState.markAnonymous()
    }

    override suspend fun ensureFresh(observedGeneration: Long): Boolean {
        val observedRevision = refreshRevision
        return withContext(Dispatchers.IO) {
        loginMutex.withLock {
            if (observedGeneration != AccountGeneration.current) return@withLock false
            if (observedRevision != refreshRevision) return@withLock lastRefreshSucceeded
            val user = SessionStore.currentUser()?.xh ?: return@withLock false
            val password = SecureCredentialVault.wisdomPassword() ?: return@withLock false
            val generation = AccountGeneration.current
            val result = authenticate(user, password)
            AccountGeneration.requireCurrent(generation)
            lastRefreshSucceeded = when (val outcome = result.valueOrNull()) {
                is LoginOutcome.Success -> { persist(outcome.user, password, generation); true }
                else -> {
                    if (result.errorOrNull() is AhuError.Unauthorized) AhuSessionState.markExpired()
                    false
                }
            }
            refreshRevision++
            lastRefreshSucceeded
        }
        }
    }

    override suspend fun refresh(realm: CampusRealm): Boolean {
        if (realm == CampusRealm.CAMPUS_CARD) return cardAuthentication.get(force = true) != null
        val epoch = AccountGeneration.current
        val observedRevision = wisdomRefreshRevision
        return if (realm == CampusRealm.WISDOM) withContext(Dispatchers.IO) {
            loginMutex.withLock {
                if (epoch != AccountGeneration.current) return@withLock false
                if (observedRevision != wisdomRefreshRevision) return@withLock lastWisdomRefreshSucceeded
                val user = SessionStore.currentUser()?.xh ?: return@withLock false
                val password = SecureCredentialVault.wisdomPassword() ?: return@withLock false
                val result = wisdomLogin(user, password)
                AccountGeneration.withCurrent(epoch) { if (result.isSuccess) saveCookies() }
                lastWisdomRefreshSucceeded = result.isSuccess
                wisdomRefreshRevision++
                lastWisdomRefreshSucceeded
            }
        } else ensureFresh(epoch)
    }

    override suspend fun refreshCas(serviceUrl: String): Boolean = withContext(Dispatchers.IO) {
        val epoch = AccountGeneration.current
        loginMutex.withLock {
        AccountGeneration.requireCurrent(epoch)
        val address = serviceUrl.toHttpUrl()
        require(address.scheme == "https" && isCentralCasLogin(serviceUrl))
        val username = SessionStore.currentUser()?.xh ?: return@withLock false
        val password = SecureCredentialVault.wisdomPassword() ?: return@withLock false
        val page = AcademicPortalHttp.requestForCas(serviceUrl, null, epoch)
        if (page.status !in 200..399) return@withLock false
        if (!isCentralCasLogin(page.url)) {
            AccountGeneration.withCurrent(epoch) { saveCookies() }
            return@withLock true
        }
        val form = Jsoup.parse(page.html).selectFirst("form#loginForm") ?: return@withLock false
        val ticket = form.selectFirst("input[name=lt]")?.attr("value")?.takeIf { it.isNotBlank() } ?: return@withLock false
        val target = page.url.toHttpUrl().resolve(form.attr("action")) ?: return@withLock false
        require(target.host == "one.ahu.edu.cn" && target.scheme == "https" &&
            target.encodedPath.substringBefore(';') == "/cas/login")
        val cipher = DES().strEnc(username + password + ticket, "1", "2", "3")
        val fields = mapOf("rsa" to cipher, "ul" to username.length.toString(), "pl" to password.length.toString())
        val device = AcademicPortalHttp.request("https://one.ahu.edu.cn/cas/device", fields + ("method" to "login"))
        if (device.status !in 200..299) return@withLock false
        val status = runCatching { JSONObject(device.html).optString("info") }.getOrDefault("")
        if (status == "unbind") {
            val confirmation = AcademicPortalHttp.request("https://one.ahu.edu.cn/cas/device", mapOf("method" to "bind2", "saveDevice" to "0"))
            if (confirmation.status !in 200..299) return@withLock false
            if (runCatching { JSONObject(confirmation.html).optString("info") }.getOrDefault("") != "ok") return@withLock false
        } else if (status != "ok") return@withLock false
        val response = AcademicPortalHttp.requestForCas(target.toString(), fields + mapOf("lt" to ticket,
            "execution" to (form.selectFirst("input[name=execution]")?.attr("value") ?: "e1s1"), "_eventId" to "submit"), epoch)
        (response.status in 200..399 && !isCentralCasLogin(response.url)).also {
            AccountGeneration.withCurrent(epoch) { if (it) saveCookies() }
        }
        }
    }

    override fun nativeCookies(realm: CampusRealm, observedGeneration: Long): String = AccountGeneration.withCurrent(observedGeneration) {
        NativeCookieCodec.encodeNative(CookieManager.cookieJar.allCookies.filter {
            permitted(realm, it.domain) || it.domain == "ahu.edu.cn"
        })
    }

    override fun postgraduateSessionUrl(): String? = graduateUrl ?: SecureStorage.getString("account.gmis_session_url")

    override suspend fun campusCardToken(): String? = cardAuthentication.get()
    override suspend fun refreshCampusCardToken(rejectedToken: String?): String? = cardAuthentication.get(rejectedToken, force = true)

    override fun acceptNativeResult(realm: CampusRealm, response: String, observedGeneration: Long): String {
        val result = JSONObject(response)
        result.optString("cookies").takeIf { it.isNotBlank() }?.let { importCookies(it, realm, observedGeneration) }
        AccountGeneration.withCurrent(observedGeneration) { saveCookies() }
        check(!result.has("error")) { "原生请求失败" }
        return result.get("result").toString()
    }

    private fun importCookies(json: String, realm: CampusRealm?, epoch: Long) {
        val values = NativeCookieCodec.decode(json).filter {
            realm == null || permitted(realm, it.domain) || it.domain == "ahu.edu.cn"
        }
        AccountGeneration.withCurrent(epoch) {
            values.forEach(CookieManager.cookieJar::addCookie)
        }
    }

    private fun saveCookies() {
        SessionStore.saveRustCookies(NativeCookieCodec.encodeFlat(CookieManager.cookieJar.allCookies))
    }

    private fun isCentralCasLogin(address: String): Boolean = address.toHttpUrl().let {
        it.host == "one.ahu.edu.cn" && it.encodedPath.substringBefore(';').trimEnd('/') == "/cas/login"
    }

    private fun permitted(realm: CampusRealm, host: String): Boolean = host == "one.ahu.edu.cn" || when (realm) {
        CampusRealm.WISDOM -> host == "adwmh.ahu.edu.cn"
        CampusRealm.ACADEMIC -> host == "jw.ahu.edu.cn"
        CampusRealm.POSTGRADUATE -> host == "gmis.ahu.edu.cn"
        CampusRealm.CAMPUS_CARD -> host == "ycard.ahu.edu.cn"
    }

    companion object {
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
