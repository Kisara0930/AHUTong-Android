package com.ahu.ahutong.plugin.account

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonStreamParser
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant

/** Handles Android flat snapshots and cookie_store 0.22's newline-delimited native format. */
internal object NativeCookieCodec {
    private val gson = Gson()
    fun decode(json: String): List<Cookie> {
        require(json.length <= 1_048_576) { "Cookie snapshot exceeds limit" }
        if (json.isBlank()) return emptyList()
        val parser = JsonStreamParser(json)
        val values = mutableListOf<JsonObject>()
        while (parser.hasNext()) {
            val value = parser.next()
            if (value.isJsonArray) value.asJsonArray.forEach { values += it.asJsonObject }
            else values += value.asJsonObject
            require(values.size <= 2048) { "Too many cookies" }
        }
        // Validate the whole snapshot before the caller commits any mutation.
        return values.map { if (it.has("raw_cookie")) native(it) else flat(it) }
    }

    private fun flat(value: JsonObject): Cookie {
        val sourceDomain = value.get("domain").asString
        val domain = schoolDomain(sourceDomain)
        val hostOnly = value.get("host_only")?.asBoolean ?: !sourceDomain.startsWith('.')
        return Cookie.Builder().name(value.get("name").asString).value(value.get("value").asString)
            .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
            .path(value.get("path")?.asString ?: "/").apply {
                if (value.get("secure")?.asBoolean == true) secure()
                if (value.get("http_only")?.asBoolean == true) httpOnly()
                value.get("expires_at")?.takeUnless { it.isJsonNull }?.asLong?.let(::expiresAt)
            }.build()
    }

    private fun native(value: JsonObject): Cookie {
        val scoped = value.getAsJsonObject("domain")
        val hostOnly = scoped.has("HostOnly")
        val domain = schoolDomain(scoped.get(if (hostOnly) "HostOnly" else "Suffix").asString)
        val path = value.getAsJsonArray("path")[0].asString
        require(path.startsWith('/')) { "Invalid cookie path" }
        val raw = value.get("raw_cookie").asString
        val parsed = requireNotNull(Cookie.parse("https://$domain/".toHttpUrl(), raw)) { "Invalid native cookie" }
        require(parsed.domain == domain) { "Native cookie scope mismatch" }
        return Cookie.Builder().name(parsed.name).value(parsed.value).path(path).apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            if (parsed.secure) secure()
            if (parsed.httpOnly) httpOnly()
            val expiry = value.get("expires")
            if (expiry?.isJsonObject == true) {
                expiresAt(Instant.parse(expiry.asJsonObject.get("AtUtc").asString).toEpochMilli())
            } else require(expiry?.asString == "SessionEnd") { "Invalid cookie expiry" }
        }.build()
    }

    fun encodeFlat(cookies: List<Cookie>): String = gson.toJson(cookies.map { cookie ->
        mapOf("name" to cookie.name, "value" to cookie.value, "domain" to cookie.domain,
            "path" to cookie.path, "secure" to cookie.secure, "http_only" to cookie.httpOnly,
            "host_only" to cookie.hostOnly, "expires_at" to cookie.expiresAt.takeIf { cookie.persistent })
    })

    /** Native format preserves host-only and absolute expiry even with older Rust flat importers. */
    fun encodeNative(cookies: List<Cookie>): String = cookies.joinToString("\n") { cookie ->
        gson.toJson(mapOf("raw_cookie" to cookie.toString(), "path" to listOf(cookie.path, true),
            "domain" to mapOf((if (cookie.hostOnly) "HostOnly" else "Suffix") to cookie.domain),
            "expires" to if (cookie.persistent) mapOf("AtUtc" to Instant.ofEpochMilli(cookie.expiresAt).toString()) else "SessionEnd"))
    }

    private fun schoolDomain(raw: String): String = raw.trimStart('.').lowercase().also {
        require(it == "ahu.edu.cn" || it.endsWith(".ahu.edu.cn")) { "Cookie domain is outside the campus" }
    }
}
