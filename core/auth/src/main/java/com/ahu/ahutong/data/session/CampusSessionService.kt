package com.ahu.ahutong.data.session

import com.ahu.ahutong.core.common.AhuResult
import com.ahu.ahutong.data.model.LoginOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/** Stable account boundary. Implementations are supplied by the mandatory account plugin. */
interface CampusSessionService : AhuSession {
    fun client(realm: CampusRealm): OkHttpClient
    suspend fun refresh(realm: CampusRealm): Boolean
    suspend fun refreshCas(serviceUrl: String): Boolean
    suspend fun importWebCookies(cookies: String, observedGeneration: Long): AhuResult<Unit>
    fun nativeCookies(realm: CampusRealm, observedGeneration: Long): String
    fun acceptNativeResult(realm: CampusRealm, response: String, observedGeneration: Long): String
    fun postgraduateSessionUrl(): String?
    suspend fun campusCardToken(): String?
    /** Multiple responses rejecting the same token share one refresh. */
    suspend fun refreshCampusCardToken(rejectedToken: String?): String?
}

enum class CampusRealm { WISDOM, ACADEMIC, POSTGRADUATE, CAMPUS_CARD }

/** A request must retain its generation and discard results after an account change. */
object AccountGeneration {
    private val mutable = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutable.asStateFlow()
    val current: Long get() = mutable.value

    @Synchronized fun advance(): Long {
        mutable.value += 1
        return mutable.value
    }

    @Synchronized fun requireCurrent(observed: Long) {
        check(observed == current) { "账号已切换，请重新操作" }
    }

    @Synchronized fun <T> withCurrent(observed: Long, operation: () -> T): T {
        requireCurrent(observed)
        return operation()
    }

    @Synchronized fun advanceAndCommit(operation: () -> Unit): Long {
        // Clear the previous identity before observers can construct models for
        // the new generation. Cookie callbacks share this monitor and wait.
        operation()
        mutable.value += 1
        return mutable.value
    }
}
