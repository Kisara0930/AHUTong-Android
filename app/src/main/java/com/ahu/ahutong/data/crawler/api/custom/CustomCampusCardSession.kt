package com.ahu.ahutong.data.crawler.api.custom

import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.data.crawler.api.custom.CustomCampusCardApi.Companion.API
import com.ahu.ahutong.data.dao.PreferencesManager
import com.ahu.ahutong.data.session.SecureCredentialVault
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自定义一卡通的会话：账密换 token、401 顶号后重登。
 *
 * 与 [com.ahu.ahutong.data.crawler.manager.TokenManager]（原有 CAS 链路）互不干扰：
 * 本对象只认用户在一卡通系统自己的账号密码，token 只存内存、不落盘。
 * 服务端是单设备登录，同账号在别处登录会把本端 token 顶掉（HTTP 200 + code:401 或 HTTP 401）。
 */
object CustomCampusCardSession {

    private val mutex = Mutex()

    @Volatile
    private var token: String? = null

    @Volatile
    private var refreshToken: String? = null

    /** 最近一次登录/刷新的失败文案，供界面显示。 */
    @Volatile
    var lastError: String? = null
        private set

    fun getToken(): String? = token

    fun clear() {
        token = null
        refreshToken = null
    }

    /** 账密是否都已配置。 */
    suspend fun hasCredentials(): Boolean = readAccount() != null && readPassword() != null

    /** 取可用 token；没有就先登录一次。登录失败返回 null 并写入 [lastError]。 */
    suspend fun awaitToken(): String? {
        token?.takeIf { it.isNotBlank() }?.let { return it }
        return mutex.withLock {
            token?.takeIf { it.isNotBlank() } ?: loginLocked()
        }
    }

    /**
     * 被拒绝的 token 需要刷新。并发调用会合并：若别的调用已经换了新 token，直接复用。
     * 优先用 refresh_token 换，失败再退回账密重登。
     */
    suspend fun refreshAfterUnauthorized(rejectedToken: String?): String? = mutex.withLock {
        val current = token
        if (rejectedToken != null && !current.isNullOrBlank() && current != rejectedToken) {
            return@withLock current
        }
        val refreshed = refreshToken?.takeIf { it.isNotBlank() }?.let { loginWithRefreshLocked(it) }
        refreshed ?: loginLocked()
    }

    private suspend fun loginWithRefreshLocked(currentRefreshToken: String): String? = try {
        val response = API.refresh(refreshToken = currentRefreshToken).execute()
        val body = response.body()
        val accessToken = body?.accessToken?.takeIf { it.isNotBlank() }
        if (response.isSuccessful && accessToken != null) {
            token = accessToken
            body.refreshToken?.takeIf { it.isNotBlank() }?.let { refreshToken = it }
            lastError = null
            accessToken
        } else {
            // 刷新失败不写 lastError：还会退回账密重登，由重登决定最终错误文案。
            null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun loginLocked(): String? {
        val account = readAccount()
        val password = readPassword()
        if (account == null) {
            lastError = "未配置自定义校园卡账号"
            return null
        }
        if (password == null) {
            lastError = "未配置自定义校园卡密码"
            return null
        }
        return try {
            val response = API.login(username = account, password = password).execute()
            val body = response.body()
            val accessToken = body?.accessToken?.takeIf { it.isNotBlank() }
            if (response.isSuccessful && accessToken != null) {
                token = accessToken
                refreshToken = body.refreshToken?.takeIf { it.isNotBlank() }
                lastError = null
                accessToken
            } else {
                val errorText = response.errorBody()?.string()
                lastError = extractError(errorText)
                    ?: "自定义校园卡登录失败（HTTP ${response.code()}）"
                null
            }
        } catch (error: Exception) {
            lastError = "自定义校园卡登录异常：${error.message ?: error.javaClass.simpleName}"
            null
        }
    }

    private suspend fun readAccount(): String? =
        PreferencesManager(AppEnvironmentHolder.context())
            .campusCardCustomAccount
            .first()
            ?.takeIf { it.isNotBlank() }

    private fun readPassword(): String? =
        SecureCredentialVault.campusCardPassword()?.takeIf { it.isNotBlank() }

    /** 取文案时按 message / msg / error_description / error 挨个找。 */
    private fun extractError(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val json = JsonParser.parseString(text)
            if (!json.isJsonObject) return@runCatching null
            val obj = json.asJsonObject
            listOf("message", "msg", "error_description", "error").firstNotNullOfOrNull { key ->
                obj.get(key)
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
