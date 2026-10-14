package com.ahu.ahutong.plugin.personalization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.core.storage.SettingsStore
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.bootstrap.BootstrapContributionStatus
import com.ahu.ahutong.personalization.context.ExamDistanceBucket
import com.ahu.ahutong.personalization.preset.*
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.runtime.*
import com.ahu.ahutong.personalization.semantic.*
import com.ahu.ahutong.personalization.settings.PersonalizationSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PersonalizationPlugin : AhuPlugin, PluginLifecycle, PluginWorkHandler, PluginContributions {
    override val meta = PluginMeta("personalization", "智能助手", "本地预测、建议、预取与可选模型贡献",
        PluginIcon.Resource(0), 0xFF7776B5, "1.0.0", "AHUTong")
    override val capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE)
    private lateinit var environment: PluginEnvironment
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val graph = MutableStateFlow<PersonalizationGraph?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val suggestionState = MutableStateFlow<PersonalizationSuggestion?>(null)
    private val contribution = MutableStateFlow(BootstrapContributionStatus())
    private var observers: Job? = null
    @Volatile private var enabled = false
    @Volatile private var learningAllowed = false
    @Volatile private var foreground = false
    @Volatile private var interactive = false
    private fun active() = graph.value?.takeIf { enabled && learningAllowed }

    override fun onCreate(environment: PluginEnvironment) {
        this.environment = environment
        HostAppVersion.initialize(environment.context)
        environment.services.publish(BehaviorRecorder::class.java, object : BehaviorRecorder {
            override fun recordOrganicAction(action: AppActionId) {
                active()?.takeIf { it.features.isAvailable(action) }?.recorder?.recordOrganicAction(action)
            }
            override fun recordContentState(domain: SemanticDomain, contentState: ContentStateBucket, freshnessBucket: Int, resultCount: ResultCountBucket, errorType: ErrorTypeBucket) {
                active()?.recorder?.recordContentState(domain, contentState, freshnessBucket, resultCount, errorType)
            }
            override fun reportCommittedMutation(mutationId: MutationId, oldValue: Any?, newValue: Any?, coarseValueBucket: String?) {
                active()?.recorder?.reportCommittedMutation(mutationId, oldValue, newValue, coarseValueBucket)
            }
            override fun recordExamDistance(bucket: ExamDistanceBucket) { active()?.recorder?.recordExamDistance(bucket) }
        })
        environment.services.publish(PresetSuggestions::class.java, object : PresetSuggestions {
            override suspend fun rank(domain: SemanticDomain) = active()?.presetSuggestions?.rank(domain).orEmpty()
            override suspend fun markExposed(candidate: PresetCandidate) = active()?.presetSuggestions?.markExposed(candidate)
            override suspend fun apply(candidate: PresetCandidate) = active()?.presetSuggestions?.apply(candidate)
            override fun expire(token: PresetInteractionToken?) { active()?.presetSuggestions?.expire(token) }
            override suspend fun recordNaturalSubmission(submission: PresetSubmission, interactionToken: PresetInteractionToken?, candidatesAtOpportunity: List<PresetCandidate>) {
                active()?.presetSuggestions?.recordNaturalSubmission(submission, interactionToken, candidatesAtOpportunity)
            }
        })
        environment.services.publish(PersonalizationSettings::class.java, object : PersonalizationSettings {
            override val contributionStatus: StateFlow<BootstrapContributionStatus> = contribution.asStateFlow()
            override fun dismissSuggestion() { active()?.runtime?.hideSuggestion() }
            override suspend fun cancelPredictivePrefetch() { graph.value?.runtime?.cancelPredictivePrefetch() }
            override suspend fun clearLearningRecord() { if (enabled) ensureGraph()?.runtime?.clearLearningRecord() }
            override suspend fun setBootstrapTrainingContribution(enabled: Boolean, includeHistorical: Boolean) {
                if (this@PersonalizationPlugin.enabled) ensureGraph()?.runtime?.setBootstrapTrainingConsent(enabled, includeHistorical)
            }
        })
        environment.services.publish(PersonalizationController::class.java, object : PersonalizationController {
            override val suggestion = suggestionState.asStateFlow()
            override fun setForeground(foreground: Boolean, interactive: Boolean) {
                this@PersonalizationPlugin.foreground = foreground
                this@PersonalizationPlugin.interactive = interactive
                active()?.runtime?.setForeground(foreground, interactive)
            }
            override fun onRouteChanged(route: String?) { active()?.runtime?.onRouteChanged(route) }
            override fun setSensitiveUiVisible(visible: Boolean) { active()?.runtime?.setSensitiveUiVisible(visible) }
            override suspend fun confirmSuggestionVisible(executionId: String) = active()?.runtime?.confirmSuggestionVisible(executionId) ?: false
            override suspend fun acceptSuggestion(executionId: String): AppActionId? {
                val current = active() ?: return null
                val action = current.runtime.acceptSuggestion(executionId) ?: return null
                if (!current.features.isAvailable(action)) return null
                if (action == AppActionId.OPEN_PAYMENT_QR && !current.features.openPaymentQr()) return null
                return action
            }
            override fun dismissSuggestion() { active()?.runtime?.dismissSuggestionByUser() }
        })
    }

    override fun onEnable() {
        enabled = true
        scope.launch { ensureGraph() }
    }

    override fun onForeground() {
        foreground = true
        interactive = true
        active()?.runtime?.setForeground(true)
    }

    override fun onBackground() {
        foreground = false
        interactive = false
        active()?.runtime?.setForeground(false)
    }

    private suspend fun ensureGraph(): PersonalizationGraph? = mutex.withLock {
        if (!enabled) return@withLock null
        graph.value?.let {
            learningAllowed = enabled && it.preferences.personalizationEnabled.first() && SessionStore.currentUser() != null
            it.runtime.setForeground(foreground && learningAllowed, interactive && learningAllowed)
            return@withLock it
        }
        try {
            val preferences = checkNotNull(environment.services.resolve(SettingsStore::class.java)) { "宿主设置服务未就绪" }
            val features = checkNotNull(environment.services.resolve(PersonalizationFeatureAccess::class.java)) { "功能能力服务未就绪" }
            val work = checkNotNull(environment.services.resolve(PluginWorkScheduler::class.java)) { "后台任务服务未就绪" }
            val created = PersonalizationGraph(environment.context, preferences, features, work)
            if (!enabled) { created.close(); return@withLock null }
            graph.value = created
            error.value = null
            observers = scope.launch {
                launch { created.runtime.uiState.collect { state ->
                    suggestionState.value = (state as? PredictionUiState.Suggestion)?.takeIf { enabled && learningAllowed }
                        ?.let { PersonalizationSuggestion(it.executionId, it.action, it.title, it.reason) }
                } }
                launch { created.runtime.bootstrapContributionStatus.collect { contribution.value = it } }
                combine(SessionStore.accountIdUpdates(), preferences.personalizationEnabled) { account, allowed -> account to allowed }
                    .distinctUntilChanged().collect { (account, allowed) ->
                        learningAllowed = enabled && allowed && account != null
                        if (learningAllowed) {
                            created.runtime.startProfile(checkNotNull(account))
                            created.runtime.setForeground(foreground, interactive)
                        } else {
                            created.runtime.setForeground(false)
                            created.runtime.stopSession("PLUGIN_OR_LEARNING_DISABLED")
                            suggestionState.value = null
                        }
                    }
            }
            created
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failure.message ?: "智能助手启动失败"; null }
    }

    override fun onDisable() {
        enabled = false
        learningAllowed = false
        suggestionState.value = null
        environment.services.resolve(PluginWorkScheduler::class.java)?.cancelAll(meta.id)
        scope.launch { closeGraph() }
    }

    private suspend fun closeGraph() = mutex.withLock {
        // A rapid disable/re-enable reconciles to the newest desired state.
        if (enabled) return@withLock
        observers?.cancelAndJoin()
        observers = null
        val previous = graph.value
        graph.value = null
        previous?.close()
    }

    override fun onDestroy() {
        enabled = false
        learningAllowed = false
        scope.launch { try { closeGraph() } finally { scope.cancel() } }
    }

    override suspend fun run(taskId: String): PluginWorkResult {
        val current = ensureGraph() ?: return if (enabled) PluginWorkResult.RETRY else PluginWorkResult.SUCCESS
        return mutex.withLock {
            if (!enabled || graph.value !== current) return@withLock PluginWorkResult.SUCCESS
            try {
                val pending = when (taskId) {
                    "bootstrap_upload" -> current.bootstrapUploader.uploadDue()
                    "quality_upload" -> current.qualityUploader.uploadDue()
                    else -> return@withLock PluginWorkResult.FAILURE
                }
                if (pending) PluginWorkResult.RETRY else PluginWorkResult.SUCCESS
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PluginWorkResult.RETRY }
        }
    }

    @Composable override fun Entry(host: PluginHostServices) {
        val current by graph.collectAsState()
        val failure by error.collectAsState()
        PersonalizationPage(current, failure, onRetry = { scope.launch { ensureGraph() } }, onBack = { (host as? PluginHostV2)?.close() })
    }

    @Composable override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        val suggestion by suggestionState.collectAsState()
        androidx.compose.material3.TextButton(onClick = onOpen) {
            androidx.compose.material3.Text(suggestion?.let { "智能助手 · ${it.title}" } ?: "智能助手 · 设置与学习状态")
        }
    }
}
