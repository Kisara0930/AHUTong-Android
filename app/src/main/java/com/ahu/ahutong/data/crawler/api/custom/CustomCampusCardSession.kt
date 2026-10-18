package com.ahu.ahutong.data.crawler.api.custom

import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.data.crawler.api.custom.CustomCampusCardApi.Companion.API
import com.ahu.ahutong.data.dao.PreferencesManager
import com.ahu.ahutong.data.session.SecureCredentialVault
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicLong
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

    /** 会话代际：每次 [clear] 自增，用来让正在进行中的登录/刷新回来时作废。 */
    private val generation = AtomicLong()

    @Volatile
    private var token: String? = null

    @Volatile
    private var refreshToken: String? = null

    /** 最近一次登录/刷新的失败文案，供界面显示。 */
    @Volatile
    var lastError: String? = null
        private set

    fun getToken(): String? = token

    /**
     * 让当前会话失效。非挂起，因此不能持 [mutex]（那样就没法在设置页直接调用）；
     * 改用代际计数：在途的登录/刷新返回时代际已变，结果会被丢弃，不会把旧密码换来的
     * token 写回内存。否则"改密时刚好有登录在飞"会让改密不生效。
     */
    fun clear() {
        generation.incrementAndGet()
        token = null
        refreshToken = null
    }

    /** 当前会话代际，登录开始时记下，提交前比对。 */
    internal fun currentGeneration(): Long = generation.get()

    /**
     * 提交一次登录结果：只有代际未变（期间没有 [clear]）时才写回内存，否则丢弃并返回 null。
     * 抽成函数是为了让"改密丢弃在途登录"这条纯逻辑能在 JVM 单测里被直接覆盖。
     */
    internal fun commitLoginResult(
        startGeneration: Long,
        accessToken: String,
        newRefreshToken: String?
    ): String? {
        if (generation.get() != startGeneration) return null
        token = accessToken
        refreshToken = newRefreshToken
        lastError = null
        return accessToken
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
        val startGeneration = currentGeneration()
        val response = API.refresh(refreshToken = currentRefreshToken).execute()
        val body = response.body()
        val accessToken = body?.accessToken?.takeIf { it.isNotBlank() }
        if (response.isSuccessful && accessToken != null) {
            // 刷新响应通常不回传 refresh_token，缺省时保留原有的，不顺手清掉。
            val nextRefreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken
            commitLoginResult(startGeneration, accessToken, nextRefreshToken)
        } else {
            // 刷新失败不写 lastError：还会退回账密重登，由重登决定最终错误文案。
            null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun loginLocked(): String? {
        val startGeneration = currentGeneration()
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
                commitLoginResult(startGeneration, accessToken, body.refreshToken?.takeIf { it.isNotBlank() })
            } else {
                val errorText = response.errorBody()?.string()
                if (currentGeneration() == startGeneration) {
                    lastError = extractError(errorText)
                        ?: "自定义校园卡登录失败（HTTP ${response.code()}）"
                }
                null
            }
        } catch (error: Exception) {
            if (currentGeneration() == startGeneration) {
                lastError = "自定义校园卡登录异常：${error.message ?: error.javaClass.simpleName}"
            }
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
