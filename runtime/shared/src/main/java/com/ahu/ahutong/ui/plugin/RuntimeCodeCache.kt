package com.ahu.ahutong.ui.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** ART may create oat/profile siblings. Only this disposable cache is used as a DexClassLoader path. */
internal object RuntimeCodeCache {
    private val processMutex = Any()

    fun prepare(cacheRoot: File, installedDirectory: File, manifest: AhupManifest): List<File> = synchronized(processMutex) {
        cacheRoot.mkdirs()
        check(cacheRoot.isDirectory) { "无法创建插件代码缓存" }
        // Main and isolated processes can prepare the same content at the same time.
        RandomAccessFile(File(cacheRoot, "prepare.lock"), "rw").use { handle ->
            handle.channel.lock().use {
                manifest.files.filter { AhupPackageVerifier.isDex(it.path) || it.path == "resources.apk" }
                    .sortedWith(compareBy<AhupFile> { it.path == "resources.apk" }.thenBy { it.path.length }.thenBy { it.path })
                    .map { declared -> prepareFile(cacheRoot, installedDirectory, manifest, declared) }
            }
        }
    }

    private fun prepareFile(cacheRoot: File, directory: File, manifest: AhupManifest, declared: AhupFile): File {
        AhupPackageVerifier.validatePath(declared.path)
        val source = File(directory, declared.path)
        require(source.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "插件代码路径越界" }
        require(matches(source, declared)) { "插件代码与签名清单不一致：${declared.path}" }
        val target = File(cacheRoot, "${manifest.id}/${manifest.versionCode}/${declared.sha256}/${declared.path}")
        require(target.canonicalPath.startsWith(cacheRoot.canonicalPath + File.separator)) { "插件代码缓存路径越界" }
        if (matches(target, declared)) {
            check(target.setReadOnly()) { "无法保护插件代码缓存" }
            return target
        }
        target.parentFile!!.mkdirs()
        check(target.parentFile!!.isDirectory) { "无法创建插件代码缓存目录" }
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(temporary).use { output ->
                // Android 14 requires the executable to become read-only before its first bytes are written.
                check(temporary.setReadOnly()) { "无法保护插件代码缓存" }
                source.inputStream().use { it.copyTo(output, 64 * 1024) }
                output.fd.sync()
            }
            require(matches(temporary, declared)) { "插件代码缓存校验失败" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            if (temporary.exists()) { temporary.setWritable(true); temporary.delete() }
        }
    }

    private fun matches(file: File, declared: AhupFile): Boolean =
        file.isFile && file.length() == declared.size && AhupPackageVerifier.digest(file) == declared.sha256
}
