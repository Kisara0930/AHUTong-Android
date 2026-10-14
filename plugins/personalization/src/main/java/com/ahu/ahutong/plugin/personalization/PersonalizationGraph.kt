package com.ahu.ahutong.plugin.personalization

import android.content.Context
import android.os.Build
import com.ahu.ahutong.core.plugin.PluginWorkScheduler
import com.ahu.ahutong.core.storage.SettingsStore
import com.ahu.ahutong.personalization.bootstrap.*
import com.ahu.ahutong.personalization.evaluation.PairedShadowModelEvaluator
import com.ahu.ahutong.personalization.inference.*
import com.ahu.ahutong.personalization.journey.*
import com.ahu.ahutong.personalization.model.ModelStateStore
import com.ahu.ahutong.personalization.prefetch.*
import com.ahu.ahutong.personalization.preset.*
import com.ahu.ahutong.personalization.profile.ProfileKeyManager
import com.ahu.ahutong.personalization.promotion.LocalPromotionManager
import com.ahu.ahutong.personalization.runtime.*
import com.ahu.ahutong.personalization.semantic.SemanticEventRecorder
import com.ahu.ahutong.personalization.settings.BehaviorPredictionSettings
import com.ahu.ahutong.personalization.storage.*
import com.ahu.ahutong.personalization.telemetry.*
import com.ahu.ahutong.personalization.training.KotlinOnDeviceTrainer

internal object HostAppVersion {
    var VERSION_CODE: Int = 0
        private set
    var VERSION_NAME: String = ""
        private set
    fun initialize(context: Context) {
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        VERSION_CODE = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        VERSION_NAME = info.versionName.orEmpty()
    }
}

/** Plugin-owned object graph; the host does not generate or bind any of these implementations. */
internal class PersonalizationGraph(
    context: Context,
    val preferences: SettingsStore,
    val features: PersonalizationFeatureAccess,
    work: PluginWorkScheduler
) {
    val database = BehaviorDatabaseFactory.open(context)
    private val dao = database.behaviorDao()
    private val state = ModelStateStore(context)
    private val bootstrapSecret = BootstrapTrainingSecretStore()
    private val lifecycle = BootstrapTrainingLifecycleStore(context)
    private val bootstrap = BootstrapTrainingDataManager(context, database, dao, bootstrapSecret, lifecycle, work)
    private val telemetrySecret = TelemetrySecretStore()
    private val aggregates = TelemetryAggregateStore(dao, TelemetryV3AggregateStore(dao))
    private val telemetry = ModelQualityTelemetryManager(context, dao, preferences, telemetrySecret, aggregates, work)
    private val trainer = KotlinOnDeviceTrainer(dao, state, bootstrap)
    private val journeyState = JourneyModelStateStore(context)
    private val journeyTrainer = JourneyOnDeviceTrainer(dao, journeyState, bootstrap)
    private val journey = JourneyPredictionEngine(database, dao, JourneyFrequencyPredictor(dao),
        TinyJourneyMlpPredictor(journeyState), journeyTrainer, JourneyPromotionManager(dao, journeyState), journeyState, aggregates)
    private val presets = PresetRankingEngine(dao, PresetModelStateStore(context), aggregates, bootstrap)
    private val paymentPreparation = PaymentQrRepository(features)
    private val prefetch = PrefetchCoordinator(context, preferences, paymentPreparation, features)
    val runtime = BehaviorPredictionRuntime(
        context, database, BehaviorDatabaseCompatibilityStore(context), dao, ProfileKeyManager(),
        DecayedFrequencyPredictor(dao), TinyMlpPredictor(state), RecentActionBaselinePredictor(), TimeBucketFrequencyBaselinePredictor(dao),
        PairedShadowModelEvaluator(dao, state, aggregates), trainer, LocalPromotionManager(dao, state), state,
        preferences, prefetch, paymentPreparation, PaymentQrOpenCommandStore(), bootstrap, telemetry, aggregates,
        SemanticEventRecorder(), journey, presets, features
    )
    val recorder = AppBehaviorRecorder(runtime)
    val presetSuggestions = AppPresetSuggestions(runtime)
    val settings = BehaviorPredictionSettings(runtime)
    val bootstrapUploader = BootstrapTrainingUploader(dao, bootstrap, bootstrapSecret, lifecycle)
    val qualityUploader = TelemetryUploader(dao, telemetrySecret)

    suspend fun close() {
        runtime.shutdown()
        prefetch.shutdown()
        journey.shutdown()
        trainer.shutdown()
        journeyTrainer.shutdown()
        presets.shutdown()
        database.close()
    }
}
