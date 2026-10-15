package com.ahu.ahutong.ui.shell

import android.content.Context
import android.os.Build
import com.ahu.ahutong.data.network.AhuHttp
import com.ahu.ahutong.ui.plugin.AhupCandidate
import com.ahu.ahutong.ui.plugin.AhupInstaller
import com.ahu.ahutong.ui.plugin.AhupManifest
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class MarketPlugin(val manifest: AhupManifest, val downloadUrl: String,
    val archiveSha256: String, val archiveSize: Long)

internal data class MarketResolution(val plugins: List<MarketPlugin>, val downloads: List<MarketPlugin>)

/** The catalogue is advisory. Every downloaded archive is independently checked by the pinned installer. */
internal class PluginMarketClient(endpoint: String) {
    private val base = checkedUri(endpoint.trim().trimEnd('/') + "/")
    private val http = AhuHttp.plain().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val gson = Gson()

    fun cancel() = http.dispatcher.cancelAll()

    suspend fun catalog(): List<MarketPlugin> = withContext(Dispatchers.IO) {
        val query = "hostApiVersion=2&runtimeVersion=2&sdk=${Build.VERSION.SDK_INT}&abis=${Build.SUPPORTED_ABIS.joinToString(",")}"
        request("v1/plugins?$query").getAsJsonArray("plugins").map { parsePlugin(it.asJsonObject) }
    }

    suspend fun resolve(context: Context, requested: Set<String>): MarketResolution = withContext(Dispatchers.IO) {
        val installed = AhupInstaller.installedIds(context).associateWith {
            requireNotNull(AhupInstaller.manifestOf(context, it)).versionCode
        }
        val body = gson.toJson(mapOf("hostApiVersion" to 2, "runtimeVersion" to "2",
            "sdk" to Build.VERSION.SDK_INT, "abis" to Build.SUPPORTED_ABIS.toList(),
            "requested" to requested.toList(), "installed" to installed))
        val response = request("v1/resolve", body)
        MarketResolution(response.getAsJsonArray("plugins").map { parsePlugin(it.asJsonObject) },
            response.getAsJsonArray("downloads").map { parsePlugin(it.asJsonObject) })
    }

    suspend fun download(context: Context, plugin: MarketPlugin, onProgress: (Long, Long) -> Unit): AhupCandidate {
        var inspected: AhupCandidate? = null
        try { return withContext(Dispatchers.IO) {
            val uri = checkedUri(base.resolve(plugin.downloadUrl).toString())
            require(uri.host.equals(base.host, ignoreCase = true) && uri.port == base.port && uri.scheme == base.scheme) {
                "插件下载地址必须与市场同源"
            }
            val directory = File(context.cacheDir, "ahup-downloads").apply { mkdirs() }
            val temporary = File(directory, "${UUID.randomUUID()}.ahup.part")
            try {
                val request = Request.Builder().url(uri.toString()).build()
                http.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                    val body = requireNotNull(response.body) { "下载响应为空" }
                    val declared = body.contentLength()
                    require(declared == -1L || declared == plugin.archiveSize) { "市场下载大小与目录不一致" }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= plugin.archiveSize) { "下载文件超出目录声明大小" }
                                output.write(buffer, 0, count)
                                hash.update(buffer, 0, count)
                                onProgress(written, plugin.archiveSize)
                            }
                            output.fd.sync()
                        }
                    }
                    require(written == plugin.archiveSize) { "插件下载不完整，请重试" }
                    val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    require(actual == plugin.archiveSha256) { "插件下载摘要不一致" }
                }
                temporary.inputStream().use { AhupInstaller.inspect(context, it) }.also {
                    inspected = it
                    require(it.manifest.id == plugin.manifest.id && it.manifest.versionCode == plugin.manifest.versionCode) {
                        "实际插件身份或版本与市场目录不一致"
                    }
                }
            } finally {
                temporary.delete()
            }
        } } catch (failure: Throwable) {
            // withContext may discard its returned value when cancellation races completion.
            inspected?.close()
            throw failure
        }
    }

    private fun request(path: String, json: String? = null): JsonObject {
        val builder = Request.Builder().url(base.resolve(path).toString())
        if (json != null) builder.post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return http.newCall(builder.build()).execute().use { response ->
            val body = requireNotNull(response.body) { "市场响应为空" }
            require(body.contentLength() <= MAX_CATALOG_BYTES) { "市场目录过大" }
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_CATALOG_BYTES) { "市场目录过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val result = runCatching { JsonParser.parseString(bytes.decodeToString()).asJsonObject }.getOrNull()
            require(response.isSuccessful) {
                result?.getAsJsonObject("error")?.get("message")?.asString ?: "市场请求失败：HTTP ${response.code}"
            }
            requireNotNull(result) { "市场响应不是有效 JSON" }.also {
                require(it.get("schemaVersion")?.asInt == 1) { "市场接口版本不兼容" }
            }
        }
    }

    private fun parsePlugin(value: JsonObject): MarketPlugin {
        val download = value.getAsJsonObject("download")
        val sha = download.get("sha256").asString
        val size = download.get("size").asLong
        require(sha.matches(Regex("[0-9a-f]{64}")) && size in 1..(256L * 1024 * 1024)) { "市场包摘要或大小无效" }
        return MarketPlugin(AhupManifest.parse(value.getAsJsonObject("manifest").toString()),
            download.get("url").asString, sha, size)
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
        fun checkedUri(value: String): URI {
            val uri = URI(value)
            require(uri.userInfo == null && uri.fragment == null && uri.query == null && !uri.host.isNullOrBlank()) {
                "请输入不含账号、查询参数的市场根地址"
            }
            val loopback = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
                "市场需要 HTTPS；本机测试可使用 http://127.0.0.1"
            }
            return uri
        }
    }
}
