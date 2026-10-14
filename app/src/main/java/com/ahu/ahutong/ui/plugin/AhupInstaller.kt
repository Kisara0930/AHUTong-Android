package com.ahu.ahutong.ui.plugin

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID

/** Official, signed AHUP v3 installer. Installed code is immutable and never stored in cache. */
object AhupInstaller {
    enum class SignatureStatus { TRUSTED, UNSIGNED, INVALID }

    internal fun root(context: Context) = File(context.filesDir, "ahup-v3")
    internal fun store(context: Context) = AhupStateStore(root(context))
    internal fun versionDir(context: Context, id: String, version: Int): File {
        AhupPackageVerifier.validateId(id)
        require(version > 0)
        return File(root(context), "packages/$id/$version")
    }

    fun installedIds(context: Context): List<String> = store(context).read().installed.keys.sorted()

    /** Read one coherent selection without changing installer state or constructing plugin code. */
    internal fun installedManifests(context: Context): List<AhupManifest> = store(context).inspect { state ->
        state.installed.map { (id, entry) -> manifestAt(context, id, entry.activeVersion) }
    }

    internal fun manifestAt(context: Context, id: String, version: Int): AhupManifest =
        AhupManifest.parse(File(versionDir(context, id, version), "manifest.json").readText())

    fun manifestOf(context: Context, id: String): AhupManifest? = runCatching {
        val state = store(context).read().installed[id] ?: return null
        manifestAt(context, id, state.activeVersion)
    }.getOrNull()

    fun activeDirectory(context: Context, id: String): File {
        val state = store(context).read().installed[id] ?: throw AhupException("插件 $id 尚未安装")
        return versionDir(context, id, state.activeVersion)
    }

    fun dexFiles(context: Context, id: String): List<File> {
        val manifest = manifestOf(context, id) ?: throw AhupException("插件 $id 清单丢失")
        return manifest.files.filter { AhupPackageVerifier.isDex(it.path) }
            .sortedWith(compareBy<AhupFile> { it.path.length }.thenBy { it.path })
            .map { File(activeDirectory(context, id), it.path) }
    }

    fun dexFile(context: Context, id: String) = dexFiles(context, id).first()
    fun iconFile(context: Context, id: String, name: String): File {
        AhupPackageVerifier.validatePath(name)
        return File(activeDirectory(context, id), name)
    }

    fun nativeLibDir(context: Context, id: String, supportedAbis: Array<String>): File? =
        supportedAbis.map { File(activeDirectory(context, id), "lib/$it") }.firstOrNull { it.isDirectory }

    fun inspect(context: Context, uri: Uri): AhupCandidate =
        (context.contentResolver.openInputStream(uri) ?: throw AhupException("无法读取插件包")).use {
            inspect(context, it)
        }

    fun inspect(context: Context, input: InputStream): AhupCandidate {
        val directory = File(context.cacheDir, "ahup-inspection/${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建插件验证目录" }
        try {
            val entries = AhupPackageVerifier.readArchiveToDirectory(input, directory)
            val manifest = AhupPackageVerifier.verifyFiles(entries, trustedPublicKey(context))
            AhupPackageVerifier.checkCompatibility(manifest, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
            return AhupCandidate(manifest, entries, directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun install(context: Context, candidate: AhupCandidate) = installBatch(context, listOf(candidate))

    /** Prepare every immutable directory first, then atomically commit one dependency-checked state. */
    @Synchronized
    fun installBatch(context: Context, candidates: List<AhupCandidate>) {
        require(candidates.isNotEmpty()) { "未选择插件包" }
        require(candidates.map { it.manifest.id }.distinct().size == candidates.size) { "不能重复安装同一插件" }
        try {
        val publicKey = trustedPublicKey(context)
        candidates.forEach {
            // Candidates may have crossed UI state boundaries: validate again, never trust a status flag.
            val verified = AhupPackageVerifier.verifyFiles(it.entries, publicKey)
            require(verified == it.manifest) { "安装候选清单已修改" }
            AhupPackageVerifier.checkCompatibility(verified, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
        }
        store(context).update { state ->
            val nextManifests = state.installed.mapValues { (id, entry) ->
                manifestAt(context, id, entry.activeVersion)
            }.toMutableMap()
            candidates.forEach { candidate ->
                val old = nextManifests[candidate.manifest.id]
                require(old == null || candidate.manifest.versionCode >= old.versionCode) {
                    "不允许安装旧版本；请使用恢复上一正常版本"
                }
                nextManifests[candidate.manifest.id] = candidate.manifest
            }
            AhupPackageVerifier.checkDependencies(nextManifests.values)
            candidates.forEach { writeVersion(context, it) }
            val installed = state.installed.toMutableMap()
            candidates.forEach { candidate ->
                val id = candidate.manifest.id
                val old = installed[id]
                installed[id] = if (old?.activeVersion == candidate.manifest.versionCode) old else InstalledAhup(
                    activeVersion = candidate.manifest.versionCode,
                    previousVersion = old?.lastGoodVersion,
                    lastGoodVersion = old?.lastGoodVersion,
                    enabled = old?.enabled ?: (id in state.required)
                )
            }
            state.copy(installed = installed)
        }
        } finally {
            candidates.forEach(AhupCandidate::close)
        }
    }

    private fun writeVersion(context: Context, candidate: AhupCandidate) {
        val manifest = candidate.manifest
        val destination = versionDir(context, manifest.id, manifest.versionCode)
        if (destination.exists()) {
            val installed = readInstalledEntries(destination)
            require(installed.keys == candidate.entries.keys && installed.all { (path, file) ->
                file.length() == candidate.entries.getValue(path).length() &&
                    AhupPackageVerifier.digest(file) == AhupPackageVerifier.digest(candidate.entries.getValue(path))
            }) { "同一个插件版本的内容不可变，请增加 versionCode" }
            return
        }
        val staging = File(root(context), "staging/${UUID.randomUUID()}")
        check(staging.mkdirs()) { "无法创建插件暂存目录" }
        try {
            candidate.entries.forEach { (path, source) ->
                AhupPackageVerifier.validatePath(path)
                val file = File(staging, path)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                FileOutputStream(file).use { output ->
                    // Android 14 requires files to be read-only before code bytes are written.
                    check(file.setReadOnly()) { "无法保护插件文件：$path" }
                    source.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                    output.fd.sync()
                }
            }
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            check(staging.renameTo(destination)) { "插件版本落盘失败" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    internal fun verifyInstalled(context: Context, id: String): AhupManifest {
        val entries = readInstalledEntries(activeDirectory(context, id))
        return AhupPackageVerifier.verifyFiles(entries, trustedPublicKey(context)).also {
            require(it.id == id) { "插件身份不一致" }
            AhupPackageVerifier.checkCompatibility(it, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
        }
    }

    private fun readInstalledEntries(directory: File): Map<String, File> {
        require(directory.isDirectory) { "插件版本文件丢失" }
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        require(files.size <= AhupPackageVerifier.MAX_ENTRIES + 2) { "插件安装目录文件超限" }
        var total = 0L
        return files.associate { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            AhupPackageVerifier.validatePath(path)
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "插件路径越界" }
            total += file.length()
            require(file.length() <= AhupPackageVerifier.MAX_FILE_BYTES && total <= AhupPackageVerifier.MAX_TOTAL_BYTES + 2 * 1024 * 1024) {
                "插件安装文件超限"
            }
            path to file
        }
    }

    fun isEnabled(context: Context, id: String): Boolean =
        store(context).read().installed[id]?.enabled == true

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val changed = mutableListOf<String>()
        store(context).update { state ->
            val installed = state.installed.toMutableMap()
            require(id in installed) { "插件 $id 尚未安装" }
            if (!enabled) {
                require(id !in state.required) { "必需插件不能停用" }
                val dependents = installed.filter { (otherId, value) -> otherId != id && value.enabled &&
                    manifestAt(context, otherId, value.activeVersion).dependencies.any { it.id == id } }.keys
                require(dependents.isEmpty()) { "请先停用依赖插件：${dependents.joinToString()}" }
                installed[id] = installed.getValue(id).copy(enabled = false)
                changed.add(id)
            } else {
                val visiting = mutableSetOf<String>()
                fun enable(pluginId: String) {
                    if (!visiting.add(pluginId)) return
                    val entry = requireNotNull(installed[pluginId]) { "缺少依赖 $pluginId" }
                    require(!HostCrashRecovery.isQuarantined(context, pluginId, entry.activeVersion)) { "插件 $pluginId 已隔离，请先安装修复版本或在恢复设置中明确重试" }
                    manifestAt(context, pluginId, entry.activeVersion).dependencies.forEach { dep ->
                        require(dep.accepts(installed[dep.id]?.activeVersion ?: 0)) { "依赖版本不兼容：${dep.id}" }
                        enable(dep.id)
                    }
                    if (!entry.enabled) changed.add(pluginId)
                    installed[pluginId] = entry.copy(enabled = true)
                }
                enable(id)
            }
            state.copy(installed = installed)
        }
        changed.forEach { RuntimePluginLoader.notifyEnabled(context, it, enabled) }
    }

    internal fun markHealthy(context: Context, id: String, version: Int) {
        store(context).update { state ->
            val entry = state.installed[id]
            if (entry == null || entry.activeVersion != version) state else
                state.copy(installed = state.installed + (id to entry.copy(lastGoodVersion = version)))
        }
    }

    /** Data migrations may be irreversible: automatic rollback is only safe within a data version. */
    @Synchronized
    fun restorePrevious(context: Context, id: String): Boolean {
        var restored = false
        store(context).update { state ->
            val entry = state.installed[id] ?: return@update state
            val previous = entry.previousVersion ?: return@update state
            if (previous == entry.activeVersion) return@update state
            require(!HostCrashRecovery.isQuarantined(context, id, previous)) { "上一版本也已因崩溃隔离，不能自动回退" }
            val oldManifest = manifestAt(context, id, previous)
            val currentManifest = manifestAt(context, id, entry.activeVersion)
            require(oldManifest.dataVersion == currentManifest.dataVersion) { "数据版本已迁移，不能直接回退代码" }
            AhupPackageVerifier.verifyFiles(readInstalledEntries(versionDir(context, id, previous)), trustedPublicKey(context))
            val manifests = state.installed.map { (key, value) ->
                if (key == id) oldManifest else manifestAt(context, key, value.activeVersion)
            }
            AhupPackageVerifier.checkDependencies(manifests)
            restored = true
            state.copy(installed = state.installed + (id to entry.copy(
                activeVersion = previous, previousVersion = null, lastGoodVersion = previous)))
        }
        return restored
    }

    /** Removing code does not delete user data. Loaded files are retained until the process ends. */
    fun uninstall(context: Context, id: String) {
        store(context).update { state ->
            require(id !in state.required) { "必需插件不能卸载" }
            require(state.installed.none { (otherId, value) -> otherId != id &&
                manifestAt(context, otherId, value.activeVersion).dependencies.any { it.id == id } }) {
                "仍有插件依赖 $id，请先卸载依赖方"
            }
            state.copy(installed = state.installed - id)
        }
        RuntimePluginLoader.notifyEnabled(context, id, false)
    }

    /** Cold-start cleanup only touches our package store, never plugin user-data directories. */
    fun pruneUnusedVersions(context: Context) {
        val loadedIds = RuntimePluginLoader.loadedIds()
        store(context).inspect { state ->
        val packages = File(root(context), "packages")
        packages.listFiles().orEmpty().filter { it.isDirectory }.forEach { plugin ->
            if (plugin.name in loadedIds) return@forEach
            val record = state.installed[plugin.name]
            val keep = listOfNotNull(record?.activeVersion, record?.previousVersion, record?.lastGoodVersion).map { it.toString() }.toSet()
            plugin.listFiles().orEmpty().filter { it.isDirectory && it.name !in keep }.forEach { obsolete ->
                require(obsolete.canonicalPath.startsWith(packages.canonicalPath + File.separator))
                obsolete.deleteRecursively()
            }
        }
        }
    }

    /** Repair exactly the installed bootstrap version; never roll user data back to an older schema. */
    fun repairBootstrapVersion(context: Context, candidate: AhupCandidate) {
        AhupPackageVerifier.verifyFiles(candidate.entries, trustedPublicKey(context))
        val id = candidate.manifest.id
        require(!RuntimePluginLoader.isLoaded(id)) { "修复前请关闭后重新启动应用" }
        store(context).inspect { state ->
        val record = state.installed[id]
        require(record == null || record.activeVersion == candidate.manifest.versionCode) {
            "内置恢复包版本与已安装版本不同，请恢复上一正常版本或导入对应版本的官方插件"
        }
        val directory = versionDir(context, id, candidate.manifest.versionCode)
        if (directory.exists()) {
            val quarantine = File(root(context), "quarantine/${id}_${UUID.randomUUID()}")
            check(quarantine.parentFile!!.isDirectory || quarantine.parentFile!!.mkdirs())
            check(directory.renameTo(quarantine)) { "无法隔离损坏的插件版本" }
            try {
                writeVersion(context, candidate)
                quarantine.deleteRecursively()
            } catch (error: Throwable) {
                if (!directory.exists()) quarantine.renameTo(directory)
                throw error
            }
        } else writeVersion(context, candidate)
        }
    }

    private fun trustedPublicKey(context: Context): PublicKey {
        val certificate = runCatching { context.assets.open("ahup_trusted_certificate.der").use { it.readBytes() } }
            .getOrElse {
                val text = context.assets.open("ahup_trusted_pubkey.txt").bufferedReader().use { it.readText() }
                Base64.getMimeDecoder().decode(text.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", ""))
            }
        return CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream()).publicKey
    }
}

class AhupCandidate internal constructor(
    val manifest: AhupManifest,
    internal val entries: Map<String, File>,
    private val inspectionDirectory: File
) : java.io.Closeable {
    val manifestRaw: ByteArray get() = entries.getValue("manifest.json").readBytes()
    override fun close() { inspectionDirectory.deleteRecursively() }
    val signatureStatus: AhupInstaller.SignatureStatus get() = AhupInstaller.SignatureStatus.TRUSTED
}

class AhupException(message: String) : Exception(message)
