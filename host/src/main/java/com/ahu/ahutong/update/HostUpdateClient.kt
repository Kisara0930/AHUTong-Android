package com.ahu.ahutong.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ahu.ahutong.BuildConfig
import com.ahu.ahutong.data.network.AhuHttp
import com.ahu.ahutong.ui.plugin.AhupInstaller
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.AhupManifest
import com.ahu.ahutong.ui.plugin.BootstrapDependencyPlan
import com.ahu.ahutong.ui.shell.PluginMarketClient
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

internal class HostUpdateClient(private val context: Context, endpoint: String) {
    private data class VerifiedDownload(val file: File, val bootstrap: List<AhupManifest>)
    private var verifiedDownload: VerifiedDownload? = null
    private val base = PluginMarketClient.checkedUri(endpoint.trim().trimEnd('/') + "/")
    private val certificate = context.assets.open("ahup_trusted_certificate.der").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it)
    }
    private val http = AhuHttp.plain().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    fun cancel() = http.dispatcher.cancelAll()
    @Suppress("DEPRECATION")
    private fun installedVersion(): Long = context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    suspend fun check(): HostUpdateMetadata? = withContext(Dispatchers.IO) {
        val url = base.resolve("v1/host/update?versionCode=${installedVersion()}&hostApiVersion=2&runtimeVersion=2&sdk=${Build.VERSION.SDK_INT}")
        http.newCall(Request.Builder().url(url.toString()).build()).execute().use { response ->
            require(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }
            val bytes = requireNotNull(response.body).byteStream().use { it.readBytesLimited(256 * 1024) }
            HostUpdateVerifier.parse(bytes.toString(Charsets.UTF_8), certificate.publicKey,
                installedVersion(), Build.VERSION.SDK_INT)
        }
    }

    suspend fun download(metadata: HostUpdateMetadata, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(context.packageName == "com.ahu.ahutong") { "预览版无法覆盖正式版，请安装签名 release APK 测试宿主更新" }
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val temporary = File(directory, "${metadata.sha256}-${UUID.randomUUID()}.part")
        val ready = File(directory, "${metadata.sha256}.apk")
        try {
            val uri = HostUpdateVerifier.sameOrigin(base, metadata.url)
            http.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
                require(response.isSuccessful) { "下载更新失败：HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == metadata.size) { "更新包长度不一致" }
                val hash = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.byteStream().use { input -> FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= metadata.size) { "下载超出声明大小" }
                        hash.update(buffer, 0, count); output.write(buffer, 0, count)
                        progress(written.toFloat() / metadata.size)
                    }
                    output.fd.sync()
                } }
                require(written == metadata.size && hash.digest().hex() == metadata.sha256) { "更新包下载不完整或摘要不一致" }
            }
            val bootstrap = verifyApk(temporary, metadata)
            check((!ready.exists() || ready.delete()) && temporary.renameTo(ready)) { "无法保存验证后的更新包" }
            verifiedDownload = VerifiedDownload(ready, bootstrap)
            ready
        } finally { temporary.delete() }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, metadata: HostUpdateMetadata): List<AhupManifest> {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)) { "APK 无法解析" }
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(info.packageName == context.packageName && version == metadata.versionCode && info.versionName == metadata.versionName) { "APK 身份与签名更新信息不一致" }
        require(info.applicationInfo?.minSdkVersion == metadata.minSdk) { "APK 系统要求不一致" }
        fun certificates(value: android.content.pm.PackageInfo): List<ByteArray> =
            (if (Build.VERSION.SDK_INT >= 28) value.signingInfo?.apkContentsSigners else value.signatures)
                .orEmpty().map { it.toByteArray() }
        val candidate = certificates(info)
        val current = certificates(context.packageManager.getPackageInfo(context.packageName, flags))
        require(candidate.size == 1 && candidate.single().contentEquals(certificate.encoded) &&
            current.any { it.contentEquals(candidate.single()) }) { "APK 签名与当前宿主不一致" }
        return ZipFile(file).use { zip ->
            if (metadata.bootstrapMode == "market") {
                val markerEntry = requireNotNull(zip.getEntry("assets/plugin-host.json")) { "更新宿主标记缺失" }
                require(markerEntry.size in 1..16384) { "更新宿主标记无效" }
                val marker = zip.getInputStream(markerEntry).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
                require(marker.get("bootstrapMode")?.asString == "market" && marker.get("hostApiVersion")?.asInt == 2 && marker.get("runtimeVersion")?.asString == "2") { "更新宿主类型不匹配" }
                val declared = marker.getAsJsonObject("requiredPluginVersions").entrySet().associate { it.key to it.value.asLong }
                require(declared == metadata.requiredPlugins) { "必要组件与签名更新信息不一致" }
                val native = com.ahu.ahutong.runtime.KernelBindings.requireEnvironment()
                native.ensureInstalled(metadata.requiredPlugins.keys.toTypedArray(), null)
                metadata.requiredPlugins.forEach { (id, version) -> require(native.installedInfo(id).getInt("versionCode") >= version) { "请先更新基础组件 $id" } }
                return@use metadata.requiredPlugins.keys.map { requireNotNull(AhupInstaller.manifestOf(context, it)) }
            }
            val bootstrapEntries = mutableListOf<java.util.zip.ZipEntry>()
            val names = hashSetOf<String>()
            var archiveSize = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.size < 65_536 && names.add(entry.name)) { "APK ZIP 条目过多或名称重复" }
                if (entry.name.startsWith("assets/bootstrap/") && entry.name.endsWith(".ahup")) {
                    require(entry.name.matches(Regex("assets/bootstrap/[a-z0-9_]+\\.ahup")) && !entry.isDirectory) { "基础插件路径无效" }
                    require(entry.size in 1..MAX_BOOTSTRAP_ARCHIVE_BYTES) { "基础插件归档大小超限" }
                    archiveSize += entry.size
                    require(archiveSize <= HostUpdateVerifier.MAX_APK_BYTES) { "基础插件归档总大小超限" }
                    bootstrapEntries.add(entry)
                    require(bootstrapEntries.size <= BootstrapDependencyPlan.MAX_EMBEDDED_PLUGINS) { "基础插件数量超限" }
                }
            }
            val marker = requireNotNull(zip.getEntry("assets/plugin-host.json")) { "APK 缺少插件宿主标记" }
            val identity = zip.getInputStream(marker).use { JsonParser.parseString(it.readBytesLimited(16384).toString(Charsets.UTF_8)).asJsonObject }
            require(identity.get("product").asString == "ahutong-plugin-host" && identity.get("hostApiVersion").asInt == 2 && identity.get("runtimeVersion").asString == "2") { "APK 插件运行环境不兼容" }
            val bootstrap = bootstrapEntries.map { entry ->
                LimitedBootstrapStream(zip.getInputStream(entry), entry.size).use {
                    AhupInstaller.inspect(context, it)
                }.use { candidatePlugin ->
                    require(entry.name == "assets/bootstrap/${candidatePlugin.manifest.id}.ahup") { "基础插件文件名与签名身份不一致" }
                    candidatePlugin.manifest
                }
            }
            val byId = bootstrap.associateBy { it.id }
            metadata.requiredPlugins.forEach { (id, versionCode) ->
                val embedded = requireNotNull(byId[id]) { "更新缺少必要插件 $id" }
                require(embedded.versionCode.toLong() >= versionCode && embedded.required) { "更新的必要插件版本不匹配" }
            }
            BootstrapDependencyPlan.resolve(AhupInstaller.installedManifests(context), bootstrap,
                AhupGate.requiredPluginIds + metadata.requiredPlugins.keys)
            bootstrap
        }
    }

    fun install(file: File) {
        require(file.isFile && file.parentFile == File(context.filesDir, "updates")) { "请先下载并验证更新包" }
        val verified = requireNotNull(verifiedDownload?.takeIf { it.file == file }) { "请先下载并验证更新包" }
        // Plugins may have changed after download or while the system install-permission screen was open.
        BootstrapDependencyPlan.resolve(AhupInstaller.installedManifests(context), verified.bootstrap,
            AhupGate.requiredPluginIds)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object { private const val MAX_BOOTSTRAP_ARCHIVE_BYTES = 256L * 1024 * 1024 }
}

/** Bound actual decompressed ZIP entry bytes even if the APK central-directory size is dishonest. */
private class LimitedBootstrapStream(input: java.io.InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
    private var read = 0L
    private fun account(count: Int) {
        if (count > 0) {
            read += count
            require(read <= limit) { "基础插件归档大小超限" }
        }
    }
    override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        `in`.read(buffer, offset, length).also(::account)
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "响应超过大小限制" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
