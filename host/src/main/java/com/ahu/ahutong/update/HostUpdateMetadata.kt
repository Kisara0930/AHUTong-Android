package com.ahu.ahutong.update

import com.google.gson.JsonParser
import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

internal data class HostUpdateMetadata(
    val versionCode: Long, val versionName: String, val minSdk: Int,
    val sha256: String, val size: Long, val url: String, val releaseNotes: String,
    val requiredPlugins: Map<String, Long>
)

/** Only fields inside the verified byte sequence can authorize an APK download. */
internal object HostUpdateVerifier {
    const val MAX_APK_BYTES = 512L * 1024 * 1024

    fun parse(envelope: String, key: PublicKey, currentVersion: Long, sdk: Int): HostUpdateMetadata? {
        require(envelope.length <= 256 * 1024) { "更新信息过大" }
        val response = JsonParser.parseString(envelope).asJsonObject
        require(response.get("schemaVersion")?.asInt == 1) { "更新接口版本不兼容" }
        if (!response.get("available").asBoolean) return null
        val bytes = Base64.getDecoder().decode(response.get("signedMetadata").asString)
        val signature = Base64.getDecoder().decode(response.get("signature").asString)
        require(bytes.size <= 128 * 1024 && signature.size <= 8192) { "更新信息过大" }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update(bytes); verify(signature)
        }) { "更新信息签名不可信" }
        val value = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(value.get("schemaVersion").asInt == 1 &&
            value.get("product").asString == "ahutong-plugin-host" &&
            value.get("packageName").asString == "com.ahu.ahutong") { "更新包不是安大通插件宿主" }
        require(value.get("hostApiVersion").asInt == 1 && value.get("runtimeVersion").asString == "1") {
            "该更新需要另一套插件运行环境，请使用完整迁移安装包"
        }
        val version = value.get("versionCode").asLong
        require(version > currentVersion) { "更新版本必须高于当前版本" }
        val minSdk = value.get("minSdk").asInt
        require(minSdk in 26..sdk) { "设备系统版本不满足更新要求" }
        val hash = value.get("sha256").asString
        val size = value.get("size").asLong
        require(hash.matches(Regex("[0-9a-f]{64}")) && size in 1..MAX_APK_BYTES) { "更新包大小或摘要无效" }
        val required = value.getAsJsonObject("requiredPluginVersions").entrySet().associate { (id, v) ->
            require(id.matches(Regex("[a-z0-9_]+")) && v.asLong > 0) { "必要插件版本无效" }
            id to v.asLong
        }
        require(required["account"] != null && required.size <= 128) { "更新缺少账户插件" }
        return HostUpdateMetadata(version, value.get("versionName").asString, minSdk, hash, size,
            value.get("url").asString, value.get("releaseNotes")?.asString.orEmpty(), required)
    }

    fun sameOrigin(base: URI, path: String): URI = base.resolve(path).also {
        require(it.userInfo == null && it.fragment == null && it.host.equals(base.host, true) &&
            it.scheme == base.scheme && it.port == base.port) { "更新下载地址必须与市场同源" }
    }
}
