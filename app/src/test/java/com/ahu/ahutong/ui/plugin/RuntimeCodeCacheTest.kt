package com.ahu.ahutong.ui.plugin

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RuntimeCodeCacheTest {
    private fun manifest(files: Map<String, ByteArray>) = AhupManifest("example", "Example", "", 0,
        "1", "AHUTong", "example.Plugin", "icon.png", emptySet(), 1, 1, "1", 26, emptySet(), emptyList(),
        files.map { (path, bytes) -> AhupFile(path, AhupPackageVerifier.digest(bytes), bytes.size.toLong()) }, false)

    private fun fixture(block: (File, File, AhupManifest) -> Unit) {
        val root = Files.createTempDirectory("ahup-art-cache").toFile()
        val source = File(root, "installed").apply { mkdirs() }
        val cache = File(root, "code-cache")
        val files = mapOf("dex/classes.dex" to ByteArray(64 * 1024) { (it % 239).toByte() },
            "resources.apk" to byteArrayOf(4, 5, 6), "icon.png" to byteArrayOf(7, 8, 9))
        files.forEach { (path, bytes) -> File(source, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
        try { block(source, cache, manifest(files)) }
        finally {
            root.walkBottomUp().filter { it.isFile }.forEach { it.setWritable(true) }
            root.deleteRecursively()
        }
    }

    @Test fun executablePathsUseVerifiedCopiesAndArtSidecarsNeverReachInstalledPackages() = fixture { source, cache, manifest ->
        val paths = RuntimeCodeCache.prepare(cache, source, manifest)
        assertEquals(listOf("classes.dex", "resources.apk"), paths.map { it.name })
        assertTrue(paths.all { it.canonicalPath.startsWith(cache.canonicalPath + File.separator) })
        val art = File(paths.first().parentFile, "oat/classes.dex.cur.prof").apply { parentFile!!.mkdirs(); writeText("ART profile") }
        assertEquals(paths, RuntimeCodeCache.prepare(cache, source, manifest))
        assertTrue(art.isFile)
        assertEquals(manifest.files.map { it.path }.toSet(), source.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(source).invariantSeparatorsPath }.toSet())
    }

    @Test fun corruptCachedBytesAreRecreatedButCorruptSignedSourcesAreRejected() = fixture { source, cache, manifest ->
        val cached = RuntimeCodeCache.prepare(cache, source, manifest).first()
        cached.setWritable(true)
        cached.writeBytes(byteArrayOf(0))
        val repaired = RuntimeCodeCache.prepare(cache, source, manifest).first()
        assertEquals(manifest.files.first().sha256, AhupPackageVerifier.digest(repaired))
        File(source, "dex/classes.dex").writeBytes(byteArrayOf(0))
        assertFailsWith<IllegalArgumentException> { RuntimeCodeCache.prepare(cache, source, manifest) }
    }

    @Test fun concurrentPreparationsExposeOnlyCompleteContent() = fixture { source, cache, manifest ->
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..6).map { executor.submit<List<String>> {
                start.await()
                RuntimeCodeCache.prepare(cache, source, manifest).map(AhupPackageVerifier::digest)
            } }
            start.countDown()
            val expected = listOf("dex/classes.dex", "resources.apk").map { path -> manifest.files.single { it.path == path }.sha256 }
            tasks.forEach { assertEquals(expected, it.get(10, TimeUnit.SECONDS)) }
            assertTrue(cache.walkTopDown().none { it.name.endsWith(".pending") })
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
