package com.ahu.ahutong.ui.plugin

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BootstrapFileLockTest {
    @Test fun concurrentEntrypointsSerializeTheWholeBootstrapOperation() {
        val directory = Files.createTempDirectory("ahup-bootstrap-lock").toFile()
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        var writes = 0
        try {
            val work = (1..6).map {
                executor.submit {
                    start.await()
                    repeat(50) {
                        BootstrapFileLock.withLock(directory) {
                            val previous = writes
                            Thread.yield()
                            writes = previous + 1
                        }
                    }
                }
            }
            start.countDown()
            work.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(300, writes)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test fun reentryFailsWithoutTakingAnOverlappingFileLockAndFailureReleasesTheLock() {
        val directory = Files.createTempDirectory("ahup-bootstrap-lock").toFile()
        try {
            val error = runCatching {
                BootstrapFileLock.withLock(directory) {
                    assertTrue(BootstrapFileLock.isHeldByCurrentThread())
                    BootstrapFileLock.withLock(directory) { fail("Reentrant initialization ran") }
                }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(BootstrapFileLock.isHeldByCurrentThread())
            assertEquals("ready", BootstrapFileLock.withLock(directory) { "ready" })
        } finally { directory.deleteRecursively() }
    }
}
