package com.ahu.ahutong.ui.plugin

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.core.plugin.PluginCapability
import com.ahu.ahutong.core.plugin.PluginContributions
import com.ahu.ahutong.core.plugin.PluginEnvironment
import com.ahu.ahutong.core.plugin.PluginHostServices
import com.ahu.ahutong.core.plugin.PluginIcon
import com.ahu.ahutong.core.plugin.PluginLifecycle
import com.ahu.ahutong.core.plugin.PluginMeta
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Metadata discovery is cheap; instances, dependency loaders and native code are created on demand. */
object RuntimePluginLoader {
    val lastErrors = CopyOnWriteArrayList<String>()
    private data class Loaded(val manifest: AhupManifest, val plugin: AhuPlugin, val loader: ClassLoader,
        val context: Context)
    private val loaded = mutableMapOf<String, Loaded>()
    private val loading = mutableSetOf<String>()
    private val failedUntilRestart = mutableSetOf<String>()
    private var hostForeground = false

    fun isLoaded(id: String): Boolean = synchronized(this) { id in loaded }
    internal fun loadedIds(): Set<String> = synchronized(this) { loaded.keys.toSet() }

    /** Returns lazy proxies, deliberately never calling a plugin constructor. */
    fun loadAll(context: Context): List<AhuPlugin> = AhupInstaller.installedIds(context)
        .filter { AhupInstaller.isEnabled(context, it) }.mapNotNull { id ->
            runCatching {
                val manifest = requireNotNull(AhupInstaller.manifestOf(context, id))
                LazyRuntimePlugin(context.applicationContext, manifest)
            }.onFailure { recordFailure(id, it) }.getOrNull()
        }

    @Synchronized
    fun ensureLoaded(context: Context, id: String): AhuPlugin {
        require(AhupInstaller.isEnabled(context, id)) { "插件 $id 未启用" }
        val selectedVersion = AhupInstaller.manifestOf(context, id)?.versionCode
        require(selectedVersion != null && !HostCrashRecovery.isQuarantined(context, id, selectedVersion)) {
            "插件 $id 已因异常退出隔离，请在修复设置中安装新版本或手动重试"
        }
        require(id !in failedUntilRestart) { "插件 $id 曾加载失败，请修复后重新启动应用" }
        loaded[id]?.let {
            val active = AhupInstaller.manifestOf(context, id)
            require(active?.versionCode == it.manifest.versionCode) { "插件已更新，请重启应用后使用" }
            return it.plugin
        }
        require(loading.add(id)) { "插件循环依赖：$id" }
        var phase = "verify"
        try {
            val manifest = AhupInstaller.verifyInstalled(context, id)
            HostCrashRecovery.beginLoading(manifest)
            phase = "dependencies"
            manifest.dependencies.forEach { dependency ->
                ensureLoaded(context, dependency.id)
                require(dependency.accepts(loaded.getValue(dependency.id).manifest.versionCode)) { "依赖版本不兼容" }
            }
            phase = "code"
            val dependencyLoaders = manifest.dependencies.map { loaded.getValue(it.id).loader }
            val parent = DependencyClassLoader(context.classLoader, dependencyLoaders)
            val installedDirectory = AhupInstaller.versionDir(context, id, manifest.versionCode)
            val classPath = RuntimeCodeCache.prepare(File(context.codeCacheDir, "ahup-runtime"), installedDirectory, manifest)
            val loader = DexClassLoader(
                classPath.joinToString(File.pathSeparator) { it.absolutePath },
                null,
                Build.SUPPORTED_ABIS.map { File(installedDirectory, "lib/$it") }.firstOrNull { it.isDirectory }?.absolutePath,
                parent
            )
            val clazz = loader.loadClass(manifest.entryClass)
            require(AhuPlugin::class.java.isAssignableFrom(clazz)) { "插件入口未实现 AhuPlugin" }
            val instance = runCatching { clazz.getField("INSTANCE").get(null) }
                .getOrElse { clazz.getDeclaredConstructor().newInstance() } as AhuPlugin
            val pluginContext = createResourceContext(context.applicationContext, installedDirectory, loader,
                manifest.dependencies.map { loaded.getValue(it.id).context.resources })
            val record = Loaded(manifest, instance, loader, pluginContext)
            // Publish before callbacks so plugins can resolve their own resource context.
            loaded[id] = record
            try {
                (instance as? PluginLifecycle)?.apply {
                    val verifiedPlugin = object : AhuPlugin by instance {
                        override val meta = PluginMeta(id, manifest.title, manifest.summary, PluginIcon.Bytes(byteArrayOf()),
                            manifest.tint, manifest.versionName, manifest.author)
                        override val capabilities = manifest.capabilities.mapNotNull {
                            runCatching { PluginCapability.valueOf(it) }.getOrNull()
                        }.toSet()
                    }
                    val host = PluginHostServicesImpl(context.applicationContext, verifiedPlugin)
                    val storage = if (PluginCapability.PLUGIN_STORAGE in verifiedPlugin.capabilities) host.storage() else null
                    onCreate(PluginEnvironment(pluginContext, id, manifest.versionCode, PluginServices.forPlugin(id), storage, host))
                    onEnable()
                    if (hostForeground) onForeground()
                }
                HostCrashRecovery.markHealthyAfterStability(context.applicationContext, id, manifest.versionCode)
            } catch (error: Throwable) {
                loaded.remove(id)
                PluginServices.revoke(id)
                runCatching { (instance as? PluginLifecycle)?.onDestroy() }
                throw error
            }
            return instance
        } catch (error: Throwable) {
            if (phase == "code") failedUntilRestart.add(id)
            recordFailure(id, error)
            if (phase == "dependencies") throw error
            // Never load a second class/native version in a failing process. Recover pointer for restart.
            val restored = runCatching { AhupInstaller.restorePrevious(context, id) }.getOrDefault(false)
            if (restored) {
                PluginRegistry.reload(context)
                throw AhupException("$id 加载失败，已恢复上一正常版本，" + if (phase == "code") "请重新启动应用" else "请重新进入功能")
            }
            throw error
        } finally {
            HostCrashRecovery.finishLoading(id)
            loading.remove(id)
        }
    }

    @Synchronized
    internal fun notifyEnabled(context: Context, id: String, enabled: Boolean) {
        if (AhupInstaller.isEnabled(context, id) != enabled) return
        val instance = loaded[id]?.plugin as? PluginLifecycle
        PluginServices.setEnabled(id, enabled)
        runCatching {
            if (enabled) {
                instance?.onEnable()
                if (hostForeground) instance?.onForeground()
            } else {
                PluginServices.resolve(com.ahu.ahutong.core.plugin.PluginNotificationScheduler::class.java)?.cancel(id)
                PluginServices.resolve(com.ahu.ahutong.core.plugin.PluginWorkScheduler::class.java)?.cancelAll(id)
                instance?.onBackground()
                instance?.onDisable()
            }
        }.onFailure { recordFailure(id, it) }
    }

    /** Account changes reach loaded plugins even after they were disabled. */
    @Synchronized
    fun notifyAccountChanged() {
        loaded.toMap().forEach { (id, record) ->
            runCatching { (record.plugin as? PluginLifecycle)?.onAccountChanged() }
                .onFailure { recordFailure(id, it) }
        }
    }

    @Synchronized
    fun notifyForeground(foreground: Boolean) {
        hostForeground = foreground
        loaded.toMap().forEach { (id, record) ->
            if (AhupInstaller.isEnabled(record.context, id)) runCatching {
                val lifecycle = record.plugin as? PluginLifecycle
                if (foreground) lifecycle?.onForeground() else lifecycle?.onBackground()
            }.onFailure { recordFailure(id, it) }
        }
    }

    fun resourceContext(context: Context, id: String): Context {
        ensureLoaded(context, id)
        return synchronized(this) {
            val record = loaded.getValue(id)
            if (context === context.applicationContext) record.context
            else PluginResourceContext(context, record.context.resources, record.loader)
        }
    }

    private fun recordFailure(id: String, error: Throwable) {
        val cause = generateSequence(error) { it.cause }.take(16).last()
        lastErrors.removeAll { it.startsWith("$id:") }
        lastErrors.add("$id: ${cause.javaClass.simpleName}: ${cause.message}")
        Log.e("RuntimePluginLoader", "插件 $id 加载失败", error)
    }

    private fun createResourceContext(base: Context, directory: File, loader: ClassLoader,
        dependencies: List<Resources>): Context {
        val archive = File(directory, "resources.apk")
        if (!archive.isFile) return PluginResourceContext(base, base.resources, loader)
        @Suppress("DEPRECATION")
        val info = requireNotNull(base.packageManager.getPackageArchiveInfo(archive.absolutePath, 0)?.applicationInfo) {
            "插件资源包无效"
        }
        info.sourceDir = archive.absolutePath
        info.publicSourceDir = archive.absolutePath
        val own = base.packageManager.getResourcesForApplication(info)
        val resources = PluginResources(own, base.resources, dependencies)
        return PluginResourceContext(base, resources, loader)
    }
}

private class DependencyClassLoader(parent: ClassLoader, private val dependencies: List<ClassLoader>) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        dependencies.forEach { loader ->
            try { return loader.loadClass(name) } catch (_: ClassNotFoundException) { /* try the next declared dependency */ }
        }
        throw ClassNotFoundException(name)
    }

    override fun findResources(name: String): java.util.Enumeration<java.net.URL> = java.util.Collections.enumeration(
        dependencies.flatMap { java.util.Collections.list(it.getResources(name)) }.distinct())

    override fun findResource(name: String): java.net.URL? = dependencies.firstNotNullOfOrNull { it.getResource(name) }
}

private class LazyRuntimePlugin(private val context: Context, private val manifest: AhupManifest) : AhuPlugin, PluginContributions {
    override val meta: PluginMeta by lazy {
        PluginMeta(manifest.id, manifest.title, manifest.summary,
            PluginIcon.Bytes(AhupInstaller.iconFile(context, manifest.id, manifest.iconFile).readBytes()),
            manifest.tint, manifest.versionName, manifest.author)
    }
    override val capabilities: Set<PluginCapability> = manifest.capabilities.mapNotNull {
        runCatching { PluginCapability.valueOf(it) }.getOrNull()
    }.toSet()

    @Composable
    override fun Entry(host: PluginHostServices) {
        val instance = RuntimePluginLoader.ensureLoaded(context, manifest.id)
        val base = LocalContext.current
        val resources = remember(base, manifest.versionCode) { RuntimePluginLoader.resourceContext(base, manifest.id) }
        CompositionLocalProvider(LocalContext provides resources) {
            instance.Entry(host)
        }
    }

    @Composable
    override fun HomeCard(host: PluginHostServices, onOpen: () -> Unit) {
        val instance = RuntimePluginLoader.ensureLoaded(context, manifest.id) as? PluginContributions ?: return
        val base = LocalContext.current
        val resources = remember(base, manifest.versionCode) { RuntimePluginLoader.resourceContext(base, manifest.id) }
        CompositionLocalProvider(LocalContext provides resources) {
            instance.HomeCard(host, onOpen)
        }
    }

    @Composable
    override fun Foreground(host: PluginHostServices) {
        val instance = RuntimePluginLoader.ensureLoaded(context, manifest.id) as? PluginContributions ?: return
        val base = LocalContext.current
        val resources = remember(base, manifest.versionCode) { RuntimePluginLoader.resourceContext(base, manifest.id) }
        CompositionLocalProvider(LocalContext provides resources) {
            instance.Foreground(host)
        }
    }
}
