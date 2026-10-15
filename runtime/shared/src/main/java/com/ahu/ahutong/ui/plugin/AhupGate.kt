package com.ahu.ahutong.ui.plugin

import android.content.Context
import java.io.File

/** Durable onboarding state; developer options never disable the official plugin platform. */
object AhupGate {
    private const val PREFS = "ahup_gate"
    private const val KEY_DEV_MODE = "dev_mode"
    val requiredPluginIds: Set<String> get() = if (com.ahu.ahutong.runtime.KernelBindings.environment != null)
        setOf("shared_runtime", "ui_runtime", "account", "appearance") else setOf("account", "appearance")
    @Volatile
    var bootstrapValidated: Boolean = false
        private set

    fun isDevMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEV_MODE, false)

    fun setDevMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEV_MODE, enabled).apply()
    }

    /** APK assets remain untouched; only verified installation files are written to private storage. */
    fun initializeBootstrap(context: Context): Result<Unit> = validateBootstrap(context.applicationContext, false)

    fun isBootstrapValidated(context: Context): Boolean = bootstrapValidated && !HostCrashRecovery.isSafeMode(context)

    /** Call on IO before any plugin/session access in Worker, receiver, provider or secondary process. */
    fun ensureBackgroundReady(context: Context): Boolean {
        // A plugin callback must never acquire bootstrap.lock while it owns the loader monitor.
        if (!isBootstrapValidated(context) &&
            (Thread.holdsLock(RuntimePluginLoader) || BootstrapFileLock.isHeldByCurrentThread())) return false
        return runCatching {
            if (!PluginPrivacyConsent.isAccepted() || !isInitialized(context)) return@runCatching false
            if (!isBootstrapValidated(context)) validateBootstrap(context.applicationContext, true).getOrThrow()
            isBootstrapValidated(context) && isInitialized(context)
        }.onFailure { error ->
            RuntimePluginLoader.lastErrors.removeAll { it.startsWith("bootstrap:") }
            RuntimePluginLoader.lastErrors.add("bootstrap: 本地初始化校验失败，原始数据已保留 (${error.javaClass.simpleName})")
        }.getOrDefault(false)
    }

    private fun validateBootstrap(context: Context, skipIfValidated: Boolean): Result<Unit> = runCatching {
        check(!Thread.holdsLock(RuntimePluginLoader)) { "请在加载插件之前完成初始化校验" }
        BootstrapFileLock.withLock(File(context.filesDir, "ahup-v3")) {
            if (!skipIfValidated || !isBootstrapValidated(context)) initializeBootstrapLocked(context)
        }
    }

    private fun initializeBootstrapLocked(context: Context) {
        bootstrapValidated = false
        check(!HostCrashRecovery.isSafeMode(context)) { "安全模式已阻止自动加载。请查看修复记录，安装更新后再重试初始化。" }
        check(PluginPrivacyConsent.isAccepted()) { "请先阅读并同意隐私政策" }
        PluginBootstrapHooks.beforeBootstrap(context)
        com.ahu.ahutong.runtime.KernelBindings.environment?.let {
            requiredPluginIds.forEach { id ->
                check(AhupInstaller.isEnabled(context, id)) { "基础组件未启用: $id" }
                AhupInstaller.verifyInstalled(context, id)
            }
            storeRequired(context)
            PluginRegistry.reload(context)
            bootstrapValidated = true
            RuntimePluginLoader.lastErrors.removeAll { error -> error.startsWith("bootstrap:") }
            return
        }
        val assetNames = context.assets.list("bootstrap").orEmpty().filter { it.endsWith(".ahup") }.sorted()
        require(assetNames.isNotEmpty()) { "基础插件缺失，请重新安装完整 APK" }
        require(assetNames.size <= BootstrapDependencyPlan.MAX_EMBEDDED_PLUGINS) { "基础插件数量超限" }
        val candidates = mutableListOf<AhupCandidate>()
        try {
        assetNames.forEach { name ->
            val candidate = context.assets.open("bootstrap/$name").use { AhupInstaller.inspect(context, it) }
            candidates += candidate
            require(name == "${candidate.manifest.id}.ahup") { "基础插件文件名与签名身份不一致" }
        }
        // Repair same-version code first, so a damaged manifest cannot block dependency resolution.
        val installedState = AhupInstaller.store(context).read().installed
        candidates.filter { installedState[it.manifest.id]?.activeVersion == it.manifest.versionCode }.forEach { candidate ->
            if (runCatching { AhupInstaller.verifyInstalled(context, candidate.manifest.id) }.isFailure) {
                AhupInstaller.repairBootstrapVersion(context, candidate)
            }
        }
        val plan = BootstrapDependencyPlan.resolve(AhupInstaller.installedManifests(context),
            candidates.map { it.manifest }, requiredPluginIds)
        val needed = plan.requiredIds
        val upgrades = candidates.filter { it.manifest.id in plan.installIds }
        if (upgrades.isNotEmpty()) AhupInstaller.installBatch(context, upgrades)
        AhupInstaller.store(context).update { state -> state.copy(required = needed) }
        needed.forEach { AhupInstaller.setEnabled(context, it, true) }
        // Verify files before lifting the gate, without constructing business entry objects.
        needed.forEach { AhupInstaller.verifyInstalled(context, it) }
        AhupInstaller.pruneUnusedVersions(context)
        PluginRegistry.reload(context)
        bootstrapValidated = true
        RuntimePluginLoader.lastErrors.removeAll { it.startsWith("bootstrap:") }
        } finally { candidates.forEach(AhupCandidate::close) }
    }

    private fun storeRequired(context: Context) {
        AhupInstaller.store(context).update { it.copy(required = requiredPluginIds) }
    }

    fun isInitialized(context: Context): Boolean = runCatching {
        val state = AhupInstaller.store(context).read()
        !HostCrashRecovery.isSafeMode(context) && state.initialized && state.required.isNotEmpty() && state.required.all {
            val entry = state.installed[it]
            entry?.enabled == true && !HostCrashRecovery.isQuarantined(context, it, entry.activeVersion) && AhupInstaller.manifestOf(context, it) != null
        }
    }.getOrDefault(false)

    fun completeInitialization(context: Context, selectedIds: Set<String> = emptySet()) {
        val state = AhupInstaller.store(context).read()
        val selected = state.required + selectedIds
        selected.forEach { id ->
            require(AhupInstaller.manifestOf(context, id) != null) { "请先安装所选插件：$id" }
            AhupInstaller.verifyInstalled(context, id)
            AhupInstaller.setEnabled(context, id, true)
        }
        AhupInstaller.store(context).update { current ->
            require(current.required.all { current.installed[it]?.enabled == true }) { "基础插件尚未就绪" }
            current.copy(initialized = true)
        }
        PluginRegistry.reload(context)
    }

    /** Safe for Worker/Receiver entry points before any business objects are created. */
    fun isFeatureEnabled(context: Context, id: String): Boolean =
        isInitialized(context) && AhupInstaller.isEnabled(context, id)

    fun resetInitialization(context: Context) {
        bootstrapValidated = false
        AhupInstaller.store(context).update { it.copy(initialized = false) }
    }

    fun requiredIds(context: Context): Set<String> = AhupInstaller.store(context).read().required

    /** Explicit optional-plugin removal preserves account/bootstrap code and all business data. */
    fun uninstallAll(context: Context) {
        val required = requiredIds(context)
        val remaining = AhupInstaller.installedIds(context).filterNot { it in required }.toMutableSet()
        while (remaining.isNotEmpty()) {
            val leaf = remaining.firstOrNull { id -> remaining.none { other ->
                AhupInstaller.manifestOf(context, other)?.dependencies?.any { it.id == id } == true
            } } ?: throw AhupException("插件依赖图异常，无法卸载")
            AhupInstaller.uninstall(context, leaf)
            remaining.remove(leaf)
        }
        PluginRegistry.reload(context)
    }
}

/** Only SDK key/value and binary storage. This is not a complete business-data erasure API. */
internal fun clearPluginSdkStorage(context: Context, pluginId: String) {
    AhupPackageVerifier.validateId(pluginId)
    require(!AhupInstaller.isEnabled(context, pluginId)) { "清除数据前请先停用插件" }
    context.getSharedPreferences("plugin_$pluginId", Context.MODE_PRIVATE).edit().clear().commit()
    File(context.filesDir, "ahup_plugins/$pluginId").deleteRecursively()
}
