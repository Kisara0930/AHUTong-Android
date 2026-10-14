package com.ahu.ahutong.plugin.storage_migration

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object MigrationArchive {
    private const val ALIAS = "ahutong.guixu-migration.archive.v1"
    private val magic = "AHUGX001".toByteArray(Charsets.US_ASCII)
    private val aad = "AHUTong GuiXu migration archive v1".toByteArray(Charsets.UTF_8)

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create && !store.containsAlias(ALIAS)) { "旧数据归档密钥不可用，请保留文件并进行恢复" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun create(archive: File, source: File, files: List<MigrationFile>, secretKey: SecretKey? = null) {
        if (archive.exists()) { verify(archive, files, secretKey); return }
        val temporary = File(archive.parentFile, "archive-${UUID.randomUUID()}.pending")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey ?: key(true)); updateAAD(aad) }
        FileOutputStream(temporary).use { output ->
            output.write(magic); output.write(cipher.iv.size); output.write(cipher.iv)
            val shield = object : FilterOutputStream(output) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                override fun close() { flush() }
            }
            ZipOutputStream(CipherOutputStream(shield, cipher)).use { zip ->
                files.forEach { item ->
                    val original = File(source, item.name)
                    require(original.canonicalFile.parentFile == source.canonicalFile)
                    check(original.length() == item.size && sha256(original) == item.sha256) { "归档时旧数据发生变化" }
                    zip.putNextEntry(ZipEntry(item.name))
                    original.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            output.fd.sync()
        }
        verify(temporary, files, secretKey)
        check(temporary.renameTo(archive)) { "加密归档提交失败，原数据已保留" }
    }

    fun verify(archive: File, files: List<MigrationFile>, secretKey: SecretKey? = null) { read(archive, files, null, secretKey) }

    /** Explicit recovery creates a fresh private directory; it never overwrites active data. */
    fun restore(archive: File, files: List<MigrationFile>, destination: File, secretKey: SecretKey? = null) {
        require(!destination.exists()) { "恢复目标必须是全新目录" }
        verify(archive, files, secretKey)
        check(destination.mkdirs())
        read(archive, files, destination, secretKey)
    }

    private fun read(archive: File, files: List<MigrationFile>, destination: File?, secretKey: SecretKey?) {
        val expected = files.associateBy { it.name }
        require(expected.size == files.size)
        FileInputStream(archive).use { input ->
            val data = DataInputStream(input)
            val header = ByteArray(magic.size).also(data::readFully)
            check(header.contentEquals(magic)) { "无法识别旧数据归档格式" }
            val length = data.readUnsignedByte()
            check(length == 12) { "归档 IV 无效" }
            val iv = ByteArray(length).also(data::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey ?: key(false), GCMParameterSpec(128, iv)); updateAAD(aad)
            }
            CipherInputStream(input, cipher).use { plaintext ->
                val zip = ZipInputStream(plaintext)
                val seen = mutableSetOf<String>()
                try {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val item = expected[entry.name] ?: error("归档包含未知文件")
                        check(!entry.isDirectory && seen.add(entry.name)) { "归档文件重复" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        val output = destination?.let { directory ->
                            val file = File(directory, item.name)
                            require(file.canonicalFile.parentFile == directory.canonicalFile)
                            FileOutputStream(file)
                        }
                        var total = 0L
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val size = zip.read(buffer)
                                if (size < 0) break
                                total += size
                                check(total <= item.size) { "归档大小不匹配" }
                                digest.update(buffer, 0, size)
                                output?.write(buffer, 0, size)
                            }
                            output?.fd?.sync()
                        } finally { output?.close() }
                        check(total == item.size && digest.digest().joinToString("") { "%02x".format(it) } == item.sha256) { "归档内容校验失败" }
                        zip.closeEntry()
                    }
                    check(seen == expected.keys) { "归档缺少源文件" }
                    // ZIP may finish before the encrypted stream; drain to verify the GCM tag.
                    val remainder = ByteArray(64 * 1024)
                    while (plaintext.read(remainder) != -1) Unit
                } finally { zip.close() }
            }
        }
    }
}
