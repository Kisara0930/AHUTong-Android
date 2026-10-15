package com.ahu.ahutong.ui.plugin

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

@androidx.annotation.Keep
private data class PluginProcessSession(val pid: Int, val processName: String, val startedAt: Long,
    val plugins: List<CrashPlugin>, val presented: List<String>, val loading: List<String>)

/** Minimal crash attribution; stack messages, request data, credentials, and full traces are never persisted. */
object HostCrashRecovery {
    private val gson = Gson()
    private val registered = ConcurrentHashMap<String, CrashPlugin>()
    private val presented = ConcurrentHashMap<String, String>()
    @Volatile private var application: Context? = null
    private val loading = ConcurrentHashMap<Long, List<CrashPlugin>>()
    private val healthScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var startedAt = 0L
    private var processName = ""
    private var installed = false
    private fun root(context: Context) = File(context.filesDir, "ahup-recovery").apply { mkdirs() }
    private fun sessionFile(context: Context, name: String) = File(root(context), "session-${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")

    /** Call once from Application.onCreate, before bootstrap or any plugin entry is invoked. */
    @Synchronized
    fun install(context: Context): List<String> {
        if (installed) return diagnostics(context)
        application = context.applicationContext
        processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName ?: context.packageName
        val previousSession = runCatching {
            gson.fromJson(sessionFile(context, processName).readText(), PluginProcessSession::class.java)
        }.getOrNull()
        if (Build.VERSION.SDK_INT >= 30 && previousSession != null) inspectNativeExit(context, previousSession)
        val reports = recover(context)
        startedAt = System.currentTimeMillis()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val causes = mutableListOf<Throwable>()
                var cause: Throwable? = error
                repeat(4) {
                    val current = cause
                    if (current != null && current !in causes) { causes += current; cause = current.cause }
                }
                val names = causes.asReversed().flatMap { it.stackTrace.take(80).map { frame -> frame.className } }
                val owner = CrashAttribution.javaOwner(names, registered.values.toList())
                    ?: loading[thread.id]?.lastOrNull()
                writeFailure(context, PluginCrash(owner, error.javaClass.name.take(120), System.currentTimeMillis()))
            }
            if (previousHandler != null) previousHandler.uncaughtException(thread, error)
            else Process.killProcess(Process.myPid())
        }
        installed = true
        persistSession()
        return reports
    }

    internal fun beginLoading(manifest: AhupManifest) {
        val descriptor = CrashPlugin(manifest.id, manifest.versionCode, manifest.entryClass.substringBeforeLast('.'),
            manifest.files.filter { it.path.startsWith("lib/") && it.path.endsWith(".so") }.map { it.path.substringAfterLast('/') })
        registered[manifest.id] = descriptor
        val thread = Thread.currentThread().id
        loading[thread] = loading[thread].orEmpty() + descriptor
        persistSession()
    }

    internal fun finishLoading(id: String) {
        val thread = Thread.currentThread().id
        loading[thread] = loading[thread].orEmpty().filterNot { it.id == id }
        persistSession()
    }

    /** Constructor success alone is insufficient; retain the old good version through startup/UI stabilization. */
    internal fun markHealthyAfterStability(context: Context, id: String, version: Int) {
        healthScope.launch {
            kotlinx.coroutines.delay(30_000)
            runCatching {
                if (RuntimePluginLoader.isLoaded(id) && AhupInstaller.isEnabled(context, id) && !isQuarantined(context, id, version))
                    AhupInstaller.markHealthy(context, id, version)
            }
        }
    }

    fun markPresented(id: String, surface: String): String {
        val token = java.util.UUID.randomUUID().toString()
        presented[token] = "$id:$surface"
        persistSession()
        return token
    }

    fun finishPresentation(token: String) {
        presented.remove(token)
        persistSession()
    }

    fun isSafeMode(context: Context): Boolean = File(root(context), "safe_mode").isFile
    fun isQuarantined(context: Context, id: String, version: Int): Boolean {
        AhupPackageVerifier.validateId(id)
        return File(root(context), "quarantine/$id-$version").isFile
    }

    /** Explicit repair action only: normal startup never clears isolation automatically. */
    fun allowRetry(context: Context, id: String, version: Int) {
        AhupPackageVerifier.validateId(id)
        File(root(context), "quarantine/$id-$version").delete()
    }
    fun leaveSafeMode(context: Context) { File(root(context), "safe_mode").delete() }
    fun diagnostics(context: Context): List<String> = runCatching {
        File(root(context), "last_recovery.txt").readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun persistSession() {
        val context = application ?: return
        if (startedAt == 0L) return
        runCatching { atomicWrite(sessionFile(context, processName), gson.toJson(PluginProcessSession(Process.myPid(),
            processName, startedAt, registered.values.toList(), presented.values.toList(),
            loading.values.flatten().map { "${it.id}:${it.version}" }))) }
    }

    private fun writeFailure(context: Context, crash: PluginCrash) {
        val directory = File(root(context), "pending").apply { mkdirs() }
        atomicWrite(File(directory, "${Process.myPid()}_${crash.at}.json"), gson.toJson(crash))
    }

    private fun recover(context: Context): List<String> {
        val reports = mutableListOf<String>()
        File(root(context), "pending").listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }.forEach { file ->
            val crash = runCatching { require(file.length() <= 16 * 1024); gson.fromJson(file.readText(), PluginCrash::class.java) }.getOrNull()
            val plugin = crash?.plugin
            if (plugin == null) {
                File(root(context), "safe_mode").writeText("unattributed_crash")
                reports += "检测到无法归属的异常退出，已进入安全模式；没有停用任何特定插件。"
            } else runCatching {
                AhupPackageVerifier.validateId(plugin.id)
                require(plugin.version > 0)
                val quarantine = File(root(context), "quarantine/${plugin.id}-${plugin.version}")
                quarantine.parentFile!!.mkdirs()
                quarantine.writeText("${crash.at}\n${crash.kind}")
                val state = AhupInstaller.store(context).read()
                val active = state.installed[plugin.id]
                if (active?.activeVersion == plugin.version) {
                    val restored = runCatching { AhupInstaller.restorePrevious(context, plugin.id) }.getOrDefault(false)
                    if (restored) reports += "${plugin.id} v${plugin.version} 异常退出，已恢复上一正常版本。"
                    else {
                        disableDependents(context, plugin.id)
                        if (plugin.id in state.required) File(root(context), "safe_mode").writeText("required_plugin_crash")
                        reports += "${plugin.id} v${plugin.version} 已隔离；代码和用户数据均已保留，请安装修复版本或手动重试。"
                    }
                }
            }.onFailure {
                File(root(context), "safe_mode").writeText("recovery_failed")
                reports += "插件恢复未完成，已进入安全模式。"
            }
            file.delete()
        }
        if (reports.isNotEmpty()) atomicWrite(File(root(context), "last_recovery.txt"), reports.joinToString("\n"))
        return reports
    }

    private fun disableDependents(context: Context, id: String) {
        AhupInstaller.store(context).update { state ->
            val disabled = mutableSetOf(id)
            var changed: Boolean
            do {
                changed = false
                state.installed.forEach { (key, entry) ->
                    if (key !in disabled && runCatching { AhupInstaller.manifestAt(context, key, entry.activeVersion)
                            .dependencies.any { it.id in disabled } }.getOrDefault(false)) changed = disabled.add(key) || changed
                }
            } while (changed)
            state.copy(installed = state.installed.mapValues { (key, entry) -> if (key in disabled) entry.copy(enabled = false) else entry })
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun inspectNativeExit(context: Context, previous: PluginProcessSession) {
        runCatching {
            val exit = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, previous.pid, 4)
                .firstOrNull { it.processName == previous.processName && it.timestamp >= previous.startedAt &&
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE } ?: return
            val marker = File(root(context), "native_${previous.pid}_${exit.timestamp}")
            if (marker.exists()) return
            // Native tombstones are read for library attribution only, bounded and never written out.
            val trace = exit.traceInputStream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(4096)
                while (out.size() < 128 * 1024) {
                    val count = input.read(bytes, 0, minOf(bytes.size, 128 * 1024 - out.size()))
                    if (count < 0) break
                    out.write(bytes, 0, count)
                }
                out.toByteArray().decodeToString()
            }.orEmpty()
            val owner = CrashAttribution.nativeOwner(trace, previous.plugins)
            writeFailure(context, PluginCrash(owner, "native", exit.timestamp))
            marker.writeText("processed")
        }
    }

    private fun atomicWrite(file: File, value: String) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${Process.myPid()}.${Thread.currentThread().id}.${System.nanoTime()}.pending")
        FileOutputStream(temporary).use { it.write(value.toByteArray()); it.fd.sync() }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
