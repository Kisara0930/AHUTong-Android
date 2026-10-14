package com.ahu.ahutong.plugin.free_classroom

import android.content.Context
import com.ahu.ahutong.core.common.AhuError
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginServiceRegistry
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.CampusSessionService
import com.ahu.ahutong.data.session.CampusRealm
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.data.model.AcademicAccountType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.preset.*
import com.ahu.ahutong.personalization.semantic.*
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.context.ExamDistanceBucket

internal object PluginScope {
    lateinit var environment: PluginEnvironment
    val session: CampusSessionService get() = requireNotNull(environment.services.resolve(CampusSessionService::class.java)) { "账户插件尚未就绪" }
    val services: PluginServiceRegistry get() = environment.services
    fun accountCache() = AccountCache(environment.context, SessionStore.currentUser()?.xh.orEmpty())
    fun requireUndergraduate() {
        check(SessionStore.isLoggedIn()) { "请先在账户组件中登录校园账号" }
        check(SessionStore.currentUser()?.academicAccountType != AcademicAccountType.POSTGRADUATE) { "研究生账号暂不支持该本科教务功能" }
    }
    suspend fun page(path: String): Pair<String,String> = withContext(Dispatchers.IO) {
        requireUndergraduate()
        val generation = AccountGeneration.current
        session.client(CampusRealm.ACADEMIC).newCall(Request.Builder().url("https://jw.ahu.edu.cn" + path).build()).execute().use { response ->
            check(response.isSuccessful) { "教务请求失败（HTTP ${response.code}）" }
            val html = response.body?.string().orEmpty()
            AccountGeneration.requireCurrent(generation)
            check(!response.request.url.encodedPath.contains("login", true) && !html.contains("cas/login",true)) { "校园登录态已失效，请重新登录" }
            response.request.url.toString() to html
        }
    }
}

internal class AccountCache(context: Context, private val account: String) {
    private val storagePrefix = "plugin.free_classroom."
    private val prefix = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ":"
    private val generation = AccountGeneration.current
    private val gson = Gson()
    fun string(key: String): String? {
        if (com.ahu.ahutong.data.security.SecureStorage.getString(storagePrefix + prefix + "migration." + key) == "removed") return null
        val encoded = com.ahu.ahutong.data.security.SecureStorage.getString(storagePrefix + prefix + key)
        if (encoded != null) return encoded
        if (account.isBlank()) return null
        val migrated = legacyValue(key) ?: return null
        put(key, migrated)
        return migrated
    }
    private fun legacy(key: String): String? {
        val box = com.ahu.ahutong.data.security.SecureBoxStore.userBox(account)
        return com.ahu.ahutong.data.security.SecureBoxStore.getOrMigrate(box,key) {
            com.ahu.ahutong.data.security.SecureBoxStore.legacyString(box,key)
        }
    }
    private fun legacyValue(key: String): String? = when(key) {
        else -> null
    }
    fun put(key: String, value: String) { synchronized(AccountGeneration) {
        AccountGeneration.requireCurrent(generation)
        com.ahu.ahutong.data.security.SecureStorage.putString(storagePrefix + prefix + key, value)
        com.ahu.ahutong.data.security.SecureStorage.putString(storagePrefix + prefix + "migration." + key, "copied")
    } }
    fun remove(key: String) { synchronized(AccountGeneration) {
        AccountGeneration.requireCurrent(generation)
        com.ahu.ahutong.data.security.SecureStorage.putString(storagePrefix + prefix + "migration." + key, "removed")
        com.ahu.ahutong.data.security.SecureStorage.remove(storagePrefix + prefix + key)
    } }
    fun <T> read(key: String, type: java.lang.reflect.Type): T? = string(key)?.let { runCatching { gson.fromJson<T>(it,type) }.getOrNull() }
    fun save(key: String, value: Any?) = put(key,gson.toJson(value))
}

internal fun Throwable.toPluginError(): AhuError = when(this) {
    is CancellationException -> throw this
    is java.io.IOException -> AhuError.Network
    else -> AhuError.Unknown(message ?: "请求失败")
}

internal object OptionalBehavior : BehaviorRecorder {
    private fun delegate() = PluginScope.services.resolve(BehaviorRecorder::class.java)
    override fun recordOrganicAction(action: AppActionId) { delegate()?.recordOrganicAction(action) }
    override fun recordContentState(domain: SemanticDomain, contentState: ContentStateBucket, freshnessBucket: Int, resultCount: ResultCountBucket, errorType: ErrorTypeBucket) { delegate()?.recordContentState(domain,contentState,freshnessBucket,resultCount,errorType) }
    override fun reportCommittedMutation(mutationId: MutationId, oldValue: Any?, newValue: Any?, coarseValueBucket: String?) { delegate()?.reportCommittedMutation(mutationId,oldValue,newValue,coarseValueBucket) }
    override fun recordExamDistance(bucket: ExamDistanceBucket) { delegate()?.recordExamDistance(bucket) }
}
internal object OptionalPresets : PresetSuggestions {
    private fun delegate() = PluginScope.services.resolve(PresetSuggestions::class.java)
    override suspend fun rank(domain: SemanticDomain): List<PresetCandidate> = delegate()?.rank(domain).orEmpty()
    override suspend fun markExposed(candidate: PresetCandidate): PresetInteractionToken? = delegate()?.markExposed(candidate)
    override suspend fun apply(candidate: PresetCandidate): AppliedPreset? = delegate()?.apply(candidate)
    override fun expire(token: PresetInteractionToken?) { delegate()?.expire(token) }
    override suspend fun recordNaturalSubmission(submission: PresetSubmission, interactionToken: PresetInteractionToken?, candidatesAtOpportunity: List<PresetCandidate>) { delegate()?.recordNaturalSubmission(submission,interactionToken,candidatesAtOpportunity) }
}
