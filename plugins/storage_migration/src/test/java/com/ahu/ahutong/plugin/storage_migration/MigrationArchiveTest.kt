package com.ahu.ahutong.plugin.storage_migration

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

class MigrationArchiveTest {
    @Test fun verifiedArchiveRestoresExactSourceAndDoesNotOverwriteTargets() {
        val root = Files.createTempDirectory("guixu-archive-test").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val data = File(source, "session").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
            val files = listOf(MigrationFile(data.name, data.length(), sha256(data)))
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val archive = File(root, "archive.gcm")
            MigrationArchive.create(archive, source, files, key)
            MigrationArchive.verify(archive, files, key)
            val restored = File(root, "restored")
            MigrationArchive.restore(archive, files, restored, key)
            assertArrayEquals(data.readBytes(), File(restored, "session").readBytes())
            assertThrows(IllegalArgumentException::class.java) { MigrationArchive.restore(archive, files, restored, key) }
            assertTrue(source.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun damagedArchiveAndWrongKeyCannotAuthorizePlaintextCleanup() {
        val root = Files.createTempDirectory("guixu-archive-integrity").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val data = File(source, "session").apply { writeText("sensitive fixture") }
            val files = listOf(MigrationFile(data.name, data.length(), sha256(data)))
            val generator = KeyGenerator.getInstance("AES").apply { init(256) }
            val key = generator.generateKey()
            val archive = File(root, "archive.gcm")
            MigrationArchive.create(archive, source, files, key)
            assertThrows(Exception::class.java) { MigrationArchive.verify(archive, files, generator.generateKey()) }
            val corrupted = archive.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            archive.writeBytes(corrupted)
            assertThrows(Exception::class.java) { MigrationArchive.verify(archive, files, key) }
            assertTrue(data.isFile)
        } finally { root.deleteRecursively() }
    }
}
