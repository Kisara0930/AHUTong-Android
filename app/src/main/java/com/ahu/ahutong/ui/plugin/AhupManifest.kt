package com.ahu.ahutong.ui.plugin

import com.google.gson.JsonParser

/** Version 3 metadata is signed in its original UTF-8 representation, not reserialized. */
data class AhupManifest(
    val id: String,
    val title: String,
    val summary: String,
    val tint: Long,
    val versionName: String,
    val author: String,
    val entryClass: String,
    val iconFile: String,
    val capabilities: Set<String>,
    val versionCode: Int,
    val hostApiVersion: Int,
    val runtimeVersion: String,
    val minSdk: Int,
    val abis: Set<String>,
    val dependencies: List<AhupDependency>,
    val files: List<AhupFile>,
    val required: Boolean,
    val dataVersion: Int = 1,
    val userVisible: Boolean = true,
    val homeCard: Boolean = false,
    val autoStart: Boolean = false,
    val foreground: Boolean = false
) {
    val version: String get() = versionName

    companion object {
        fun parse(raw: String): AhupManifest {
            val obj = JsonParser.parseString(raw).asJsonObject
            fun text(key: String, default: String = "") = obj.get(key)?.asString ?: default
            fun number(key: String, default: Int) = obj.get(key)?.asInt ?: default
            fun strings(key: String): Set<String> = obj.getAsJsonArray(key)
                ?.map { it.asString }?.toSet().orEmpty()
            require(number("formatVersion", 0) == 3) { "仅支持已签名的 AHUP v3 插件，请下载新版插件包" }
            require(obj.get("release")?.asBoolean != false) { "正式宿主不接受开发插件包" }
            return AhupManifest(
                id = obj.get("id").asString,
                title = obj.get("title").asString,
                summary = text("summary"),
                tint = text("tint", "0xFF607D8B").removePrefix("0x").toLong(16),
                versionName = obj.get("versionName").asString,
                author = text("author", "AHUTong"),
                entryClass = obj.get("entryClass").asString,
                iconFile = text("icon", "icon.png"),
                capabilities = strings("capabilities"),
                versionCode = obj.get("versionCode").asInt,
                hostApiVersion = obj.get("hostApiVersion").asInt,
                runtimeVersion = obj.get("runtimeVersion").asString,
                minSdk = number("minSdk", 26),
                abis = strings("abis"),
                dependencies = obj.getAsJsonArray("dependencies")?.map {
                    val dep = it.asJsonObject
                    AhupDependency(dep.get("id").asString, dep.get("minVersion").asInt,
                        dep.get("maxVersion")?.takeUnless { v -> v.isJsonNull }?.asInt)
                }.orEmpty(),
                files = obj.getAsJsonArray("files").map {
                    val file = it.asJsonObject
                    AhupFile(file.get("path").asString, file.get("sha256").asString,
                        file.get("size").asLong)
                },
                required = obj.get("required")?.asBoolean ?: false,
                dataVersion = number("dataVersion", 1),
                userVisible = obj.get("userVisible")?.asBoolean ?: true,
                homeCard = obj.get("homeCard")?.asBoolean ?: false,
                autoStart = obj.get("autoStart")?.asBoolean ?: false,
                foreground = obj.get("foreground")?.asBoolean ?: false
            ).also(AhupPackageVerifier::validateManifest)
        }
    }
}

data class AhupDependency(val id: String, val minVersion: Int, val maxVersion: Int? = null) {
    fun accepts(version: Int) = version >= minVersion && (maxVersion == null || version <= maxVersion)
}

data class AhupFile(val path: String, val sha256: String, val size: Long)
