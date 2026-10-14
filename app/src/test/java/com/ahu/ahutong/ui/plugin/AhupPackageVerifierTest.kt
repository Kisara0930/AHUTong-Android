package com.ahu.ahutong.ui.plugin

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AhupPackageVerifierTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val payload = linkedMapOf("dex/classes.dex" to byteArrayOf(1, 2, 3), "icon.png" to byteArrayOf(4, 5))

    private fun archive(id: String = "example", version: Int = 1, dependencies: List<Map<String, Any>> = emptyList(),
        extras: Map<String, Any> = emptyMap()): Map<String, ByteArray> {
        val manifest = linkedMapOf<String, Any>(
            "formatVersion" to 3, "id" to id, "title" to "测试插件", "entryClass" to "example.Plugin",
            "versionCode" to version, "versionName" to "1.0", "hostApiVersion" to 1,
            "runtimeVersion" to "1", "minSdk" to 26, "abis" to emptyList<String>(),
            "capabilities" to listOf("NETWORK"), "dependencies" to dependencies,
            "files" to payload.map { (path, bytes) -> mapOf("path" to path,
                "size" to bytes.size, "sha256" to AhupPackageVerifier.digest(bytes)) }
        ).apply { putAll(extras) }
        val raw = Gson().toJson(manifest).toByteArray()
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(key.private); update(raw) }
        return payload + mapOf("manifest.json" to raw, "signature.sig" to signer.sign())
    }

    private fun verify(entries: Map<String, ByteArray>) = AhupPackageVerifier.verify(entries, key.public)

    @Test fun acceptsSignedManifestAndEveryDeclaredPayload() {
        val manifest = verify(archive())
        assertEquals("example", manifest.id)
        assertEquals(1, manifest.versionCode)
        assertEquals(2, manifest.files.size)
    }

    @Test fun streamsSignedPackagesToDiskAndDetectsTamperingBeforeInstall() {
        val directory = Files.createTempDirectory("ahup-inspection-test").toFile()
        try {
            val zipped = ByteArrayOutputStream()
            ZipOutputStream(zipped).use { zip -> archive().forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            } }
            val files = AhupPackageVerifier.readArchiveToDirectory(zipped.toByteArray().inputStream(), directory)
            assertEquals("example", AhupPackageVerifier.verifyFiles(files, key.public).id)
            files.getValue("dex/classes.dex").writeBytes(byteArrayOf(9, 9, 9))
            assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.verifyFiles(files, key.public) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsMetadataTamperingEvenWhenExecutableUnchanged() {
        val entries = archive()
        val raw = entries.getValue("manifest.json").decodeToString().replace("NETWORK", "CAMERA").toByteArray()
        assertFailsWith<IllegalArgumentException> { verify(entries + ("manifest.json" to raw)) }
    }

    @Test fun rejectsUnsignedUntrustedMissingChangedAndExtraFiles() {
        val entries = archive()
        assertFailsWith<IllegalArgumentException> { verify(entries - "signature.sig") }
        val foreign = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.verify(entries, foreign.public) }
        assertFailsWith<IllegalArgumentException> { verify(entries - "icon.png") }
        assertFailsWith<IllegalArgumentException> { verify(entries + ("icon.png" to byteArrayOf(9, 8))) }
        assertFailsWith<IllegalArgumentException> { verify(entries + ("lib/arm64-v8a/evil.so" to byteArrayOf(1))) }
    }

    @Test fun rejectsUnsafeZipPathsAndDuplicateEntries() {
        listOf("../escape", "/absolute", "a/../b", "C:/drive", "a\\b", "a//b", "./b", "a\u0000b").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { AhupPackageVerifier.validatePath(path) }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("aa")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry("bb")); it.write(2); it.closeEntry()
        }
        // ZIP writer prevents duplicate names. Mutate both local and directory names to an identical name.
        val duplicate = output.toByteArray()
        for (index in 0 until duplicate.size - 1) if (duplicate[index] == 'b'.code.toByte() && duplicate[index + 1] == 'b'.code.toByte()) {
            duplicate[index] = 'a'.code.toByte(); duplicate[index + 1] = 'a'.code.toByte()
        }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.readArchive(duplicate.inputStream()) }
    }

    @Test fun rejectsZipBombWithUnknownCompressedEntrySizeWhileStreaming() {
        // Data-descriptor entries report unknown size; enforce the limit during decompression.
        val huge = ByteArrayOutputStream()
        ZipOutputStream(huge).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write(ByteArray(1024 * 1024 + 1)); it.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.readArchive(huge.toByteArray().inputStream()) }
    }

    @Test fun checksNativeAbiHostVersionAndSdk() {
        val ordinary = verify(archive())
        AhupPackageVerifier.checkCompatibility(ordinary, 26, listOf("arm64-v8a"))
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkCompatibility(ordinary.copy(hostApiVersion = 2), 36, listOf("arm64-v8a")) }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkCompatibility(ordinary.copy(runtimeVersion = "2"), 36, listOf("arm64-v8a")) }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkCompatibility(ordinary.copy(minSdk = 36), 35, listOf("arm64-v8a")) }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkCompatibility(ordinary.copy(abis = setOf("x86_64")), 36, listOf("arm64-v8a")) }
    }

    @Test fun validatesReverseDependenciesMissingDependenciesAndCycles() {
        val account = verify(archive("account", 2))
        val schedule = verify(archive("schedule", dependencies = listOf(mapOf("id" to "account", "minVersion" to 1, "maxVersion" to 2))))
        AhupPackageVerifier.checkDependencies(listOf(account, schedule))
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkDependencies(listOf(schedule)) }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkDependencies(listOf(account.copy(versionCode = 3), schedule)) }
        assertFailsWith<IllegalArgumentException> { AhupPackageVerifier.checkDependencies(listOf(account.copy(dependencies = listOf(AhupDependency("schedule", 1))), schedule)) }
    }

    @Test fun atomicStatePreservesOldSelectionWhenTransactionFails() {
        val root = Files.createTempDirectory("ahup-state-test").toFile()
        try {
            val store = AhupStateStore(root)
            val original = store.update { it.copy(installed = mapOf("account" to InstalledAhup(1, enabled = true))) }
            assertFailsWith<IllegalStateException> { store.update { throw IllegalStateException("simulated disk preparation failure") } }
            assertEquals(original, store.read())
            val next = store.update { it.copy(installed = it.installed + ("schedule" to InstalledAhup(2))) }
            assertEquals(setOf("account", "schedule"), next.installed.keys)
            assertFalse(next.installed.getValue("schedule").enabled)
            assertTrue(next.installed.getValue("account").enabled)
            assertEquals(next, AhupStateStore(root).read())
        } finally { root.deleteRecursively() }
    }
}
