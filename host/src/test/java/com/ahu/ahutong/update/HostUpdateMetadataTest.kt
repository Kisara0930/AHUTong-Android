package com.ahu.ahutong.update

import com.google.gson.JsonObject
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.*

class HostUpdateMetadataTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun metadata() = JsonObject().apply {
        addProperty("schemaVersion", 1); addProperty("product", "ahutong-plugin-host")
        addProperty("packageName", "com.ahu.ahutong"); addProperty("versionCode", 304003)
        addProperty("versionName", "3.4.2"); addProperty("hostApiVersion", 2)
        addProperty("runtimeVersion", "2"); addProperty("minSdk", 26)
        addProperty("sha256", "a".repeat(64)); addProperty("size", 100)
        addProperty("url", "/v1/host/packages/304003/test.apk"); addProperty("releaseNotes", "修复")
        add("requiredPluginVersions", JsonObject().apply { addProperty("account", 1) })
    }
    private fun envelope(value: JsonObject): JsonObject {
        val bytes = value.toString().toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(bytes); sign() }
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("available", true)
            addProperty("signedMetadata", Base64.getEncoder().encodeToString(bytes))
            addProperty("signature", Base64.getEncoder().encodeToString(signed))
        }
    }
    private fun parse(value: JsonObject) = HostUpdateVerifier.parse(value.toString(), keys.public, 304002, 36)

    @Test fun onlySignedFieldsAuthorizeDownload() {
        val response = envelope(metadata()).apply { add("metadata", metadata().apply { addProperty("url", "https://attacker.invalid/a.apk") }) }
        assertEquals("/v1/host/packages/304003/test.apk", parse(response)?.url)
    }
    @Test fun modifiedMetadataAndWrongKeyAreRejected() {
        val response = envelope(metadata())
        response.addProperty("signedMetadata", Base64.getEncoder().encodeToString(metadata().apply { addProperty("size", 101) }.toString().toByteArray()))
        assertFailsWith<IllegalArgumentException> { parse(response) }
        val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<IllegalArgumentException> { HostUpdateVerifier.parse(envelope(metadata()).toString(), wrong.public, 304002, 36) }
    }
    @Test fun downgradeLegacyProductAndIncompatibleRuntimeAreRejected() {
        listOf("versionCode" to "304002", "product" to "legacy", "runtimeVersion" to "3", "minSdk" to "37").forEach { (field, value) ->
            assertFailsWith<IllegalArgumentException> { parse(envelope(metadata().apply { addProperty(field, value) })) }
        }
    }
    @Test fun nativeMarketBootstrapRequiresAllFoundationIds() {
        val value = metadata().apply { addProperty("bootstrapMode", "market") }
        assertFailsWith<IllegalArgumentException> { parse(envelope(value)) }
        value.add("requiredPluginVersions", JsonObject().apply {
            addProperty("shared_runtime", 1); addProperty("ui_runtime", 1)
            addProperty("account", 4); addProperty("appearance", 3)
        })
        assertEquals("market", parse(envelope(value))?.bootstrapMode)
    }
    @Test fun downloadCannotEscapeOrigin() {
        val base = URI("https://market.example/root/")
        assertEquals("https://market.example/a.apk", HostUpdateVerifier.sameOrigin(base, "/a.apk").toString())
        listOf("http://market.example/a", "https://evil.example/a", "https://user@market.example/a", "https://market.example:444/a").forEach {
            assertFailsWith<IllegalArgumentException> { HostUpdateVerifier.sameOrigin(base, it) }
        }
    }
}
