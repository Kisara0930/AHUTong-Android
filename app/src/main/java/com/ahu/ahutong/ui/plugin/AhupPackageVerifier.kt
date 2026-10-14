package com.ahu.ahutong.ui.plugin

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.zip.ZipInputStream

/** Pure JVM validation shared by inspection, installation, and cold-start integrity checks. */
internal object AhupPackageVerifier {
    const val MANIFEST = "manifest.json"
    const val SIGNATURE = "signature.sig"
    const val MAX_FILE_BYTES = 128L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    const val MAX_ENTRIES = 4096
    const val HOST_API_VERSION = 1
    const val RUNTIME_VERSION = "1"
    private val pluginId = Regex("[a-z][a-z0-9_]{1,63}")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val dexPath = Regex("(?:dex/classes(?:[2-9]|[1-9][0-9]+)?\\.dex|plugin\\.dex)")

    fun validateId(id: String) {
        require(pluginId.matches(id)) { "无效插件 ID：$id" }
    }

    fun validatePath(path: String) {
        require(path.isNotEmpty() && path.length <= 240 && !path.startsWith('/') &&
            '\\' !in path && ':' !in path && path.none { it.code < 32 || it.code == 127 } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "插件包含不安全路径：$path"
        }
    }

    fun isDex(path: String) = dexPath.matches(path)

    fun validateManifest(manifest: AhupManifest) {
        validateId(manifest.id)
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank()) { "插件版本无效" }
        require(manifest.dataVersion > 0) { "数据版本无效" }
        require(manifest.entryClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) {
            "插件入口类名无效"
        }
        require(manifest.minSdk >= 26) { "插件最低系统版本无效" }
        require(manifest.files.size in 1..MAX_ENTRIES) { "插件文件数量超限" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size) { "清单存在重复文件" }
        require(manifest.dependencies.map { it.id }.distinct().size == manifest.dependencies.size) { "插件依赖重复" }
        manifest.dependencies.forEach {
            validateId(it.id)
            require(it.id != manifest.id && it.minVersion > 0 && (it.maxVersion == null || it.maxVersion >= it.minVersion)) {
                "无效的插件依赖：${it.id}"
            }
        }
        var total = 0L
        manifest.files.forEach {
            validatePath(it.path)
            require(it.path != MANIFEST && it.path != SIGNATURE) { "清单不能将自身或签名列为载荷" }
            require(sha256.matches(it.sha256) && it.size in 0..MAX_FILE_BYTES) { "无效文件摘要或大小：${it.path}" }
            total += it.size
            require(total <= MAX_TOTAL_BYTES) { "插件解压大小超限" }
        }
        require(manifest.files.any { isDex(it.path) }) { "插件缺少 DEX 代码" }
        require(manifest.files.any { it.path == manifest.iconFile }) { "插件缺少图标" }
        manifest.files.filter { it.path.startsWith("lib/") }.forEach {
            val parts = it.path.split('/')
            require(parts.size == 3 && parts[1] in manifest.abis && parts[2].endsWith(".so")) {
                "原生库必须声明 ABI：${it.path}"
            }
        }
        require(manifest.abis.all { abi -> manifest.files.any { it.path.startsWith("lib/$abi/") } }) {
            "插件声明的 ABI 缺少原生库"
        }
    }

    fun readArchive(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validatePath(if (entry.isDirectory) name.removeSuffix("/") else name)
                require(seen.add(name)) { "插件包含重复 ZIP 条目：$name" }
                require(seen.size <= MAX_ENTRIES + 2) { "插件 ZIP 条目过多" }
                require(!entry.isDirectory) { "插件不接受目录条目，请仅打包文件：$name" }
                val limit = when (name) { MANIFEST -> 1024L * 1024; SIGNATURE -> 16L * 1024; else -> MAX_FILE_BYTES }
                require(entry.size <= limit) { "插件文件超限：$name" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count
                    total += count
                    require(size <= limit && total <= MAX_TOTAL_BYTES + 1024 * 1024 + 16 * 1024) { "插件解压大小超限" }
                    output.write(buffer, 0, count)
                }
                entries[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "不是有效的 .ahup ZIP 插件包" }
        return entries
    }

    fun verify(entries: Map<String, ByteArray>, publicKey: PublicKey): AhupManifest {
        val raw = requireNotNull(entries[MANIFEST]) { "插件缺少 manifest.json" }
        val signed = requireNotNull(entries[SIGNATURE]) { "拒绝未签名插件" }
        require(signed.isNotEmpty()) { "拒绝未签名插件" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(raw)
        require(verifier.verify(signed)) { "插件签名无效或不是受信任的官方插件" }
        val manifest = AhupManifest.parse(raw.decodeToString(throwOnInvalidSequence = true))
        val expected = manifest.files.map { it.path }.toSet() + setOf(MANIFEST, SIGNATURE)
        require(entries.keys == expected) { "插件文件与签名清单不一致" }
        manifest.files.forEach {
            val bytes = entries.getValue(it.path)
            require(bytes.size.toLong() == it.size && digest(bytes) == it.sha256) { "插件文件被修改：${it.path}" }
        }
        return manifest
    }

    /** File-backed inspection keeps a whole market selection out of the app's Java heap. */
    fun readArchiveToDirectory(input: InputStream, directory: File): Map<String, File> {
        require(directory.isDirectory && directory.listFiles().orEmpty().isEmpty()) { "插件暂存目录不是空目录" }
        val entries = linkedMapOf<String, File>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validatePath(if (entry.isDirectory) name.removeSuffix("/") else name)
                require(!entry.isDirectory) { "插件不接受目录条目" }
                require(name !in entries) { "插件包含重复 ZIP 条目：$name" }
                require(entries.size < MAX_ENTRIES + 2) { "插件 ZIP 条目过多" }
                val limit = when (name) { MANIFEST -> 1024L * 1024; SIGNATURE -> 16L * 1024; else -> MAX_FILE_BYTES }
                require(entry.size <= limit) { "插件文件超限：$name" }
                val output = File(directory, name)
                require(output.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "插件路径越界" }
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                FileOutputStream(output).use { file ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        size += count; total += count
                        require(size <= limit && total <= MAX_TOTAL_BYTES + 1024 * 1024 + 16 * 1024) { "插件解压大小超限" }
                        file.write(buffer, 0, count)
                    }
                }
                entries[name] = output
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "不是有效的 .ahup 插件包" }
        return entries
    }

    fun verifyFiles(entries: Map<String, File>, publicKey: PublicKey): AhupManifest {
        val manifestFile = requireNotNull(entries[MANIFEST]) { "插件缺少 manifest.json" }
        val signatureFile = requireNotNull(entries[SIGNATURE]) { "拒绝未签名插件" }
        require(manifestFile.length() in 1..(1024 * 1024) && signatureFile.length() in 1..(16 * 1024)) { "插件元数据大小超限" }
        val raw = manifestFile.readBytes()
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(raw)
        require(verifier.verify(signatureFile.readBytes())) { "插件签名无效或不是受信任的官方插件" }
        val manifest = AhupManifest.parse(raw.decodeToString(throwOnInvalidSequence = true))
        require(entries.keys == manifest.files.map { it.path }.toSet() + setOf(MANIFEST, SIGNATURE)) { "插件文件与签名清单不一致" }
        manifest.files.forEach { declared ->
            val file = entries.getValue(declared.path)
            require(file.isFile && file.length() == declared.size && digest(file) == declared.sha256) { "插件文件被修改：${declared.path}" }
        }
        return manifest
    }

    fun checkCompatibility(manifest: AhupManifest, sdk: Int, supportedAbis: Collection<String>) {
        require(manifest.hostApiVersion == HOST_API_VERSION && manifest.runtimeVersion == RUNTIME_VERSION) {
            "插件与当前宿主不兼容，请更新应用或下载兼容插件"
        }
        require(sdk >= manifest.minSdk) { "插件需要 Android API ${manifest.minSdk}" }
        require(manifest.abis.isEmpty() || manifest.abis.any { it in supportedAbis }) { "插件不支持本机 CPU 架构" }
    }

    /** Validate the whole prospective installation, including reverse dependencies and cycles. */
    fun checkDependencies(manifests: Collection<AhupManifest>) {
        val byId = manifests.associateBy { it.id }
        require(byId.size == manifests.size) { "安装事务包含重复插件" }
        val complete = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in complete) return
            require(visiting.add(id)) { "插件循环依赖：$id" }
            byId.getValue(id).dependencies.forEach { dep ->
                val installed = requireNotNull(byId[dep.id]) { "$id 缺少依赖 ${dep.id}" }
                require(dep.accepts(installed.versionCode)) { "$id 需要兼容版本的 ${dep.id}" }
                visit(dep.id)
            }
            visiting.remove(id)
            complete.add(id)
        }
        byId.keys.forEach(::visit)
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
