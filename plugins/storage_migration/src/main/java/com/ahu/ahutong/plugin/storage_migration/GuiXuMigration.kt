package com.ahu.ahutong.plugin.storage_migration

import android.content.Context
import android.util.AtomicFile
import com.ahu.ahutong.data.security.LegacyNativeStorage
import com.ahu.ahutong.data.security.SecureStorage
import com.ahu.ahutong.sdk.nativebridge.StorageNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal class GuiXuMigration(context: Context) : LegacyNativeStorage.Reader {
    private val source = File(context.filesDir, "rust-sdk").canonicalFile
    private val root = File(context.filesDir, "ahup_migrations/guixu-v1").canonicalFile.apply { mkdirs() }
    private val snapshot = File(root, "snapshot")
    private val stateFile = AtomicFile(File(root, "state.json"))
    private val archive = File(root, "legacy-guixu.zip.gcm")
    private var state = readState()

    @Synchronized fun migrate() {
        if (state.length() > 0) check(state.optInt("schemaVersion") == 1 && state.optString("phase") in setOf("copy_complete", "archived", "complete")) {
            "迁移记录版本无法识别，请保留数据并修复"
        }
        if (state.optString("phase") == "complete") {
            check(archive.isFile) { "迁移归档缺失，请保留现有数据并修复" }
            check(source.listFiles().isNullOrEmpty()) { "迁移完成后又发现旧格式数据，请先检查回退版本产生的数据" }
            return
        }
        if (state.optString("phase") == "archived") {
            MigrationArchive.verify(archive, files())
            verifyTargets()
            cleanPlaintext()
            complete()
            return
        }
        if (!source.isDirectory) {
            check(!snapshot.exists() && state.length() == 0) { "旧存储迁移尚未完成，但源目录缺失" }
            // New installations never need this plugin, but accidental loading is harmless.
            return
        }
        if (state.optString("phase").isBlank()) prepareSnapshot()
        check(snapshot.isDirectory) { "迁移快照缺失；原目录已保留，请修复后重试" }
        val entries = state.getJSONArray("keys")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val box = entry.getString("box")
            val key = entry.getString("key")
            val destinationKey = "$box.$key"
            if (entry.optString("status") == "committed") {
                val committed = SecureStorage.getStringStrict(destinationKey) ?: error("已迁移值缺失，不能跳过恢复")
                check(sha256(committed.toByteArray()) == entry.getString("targetSha256")) { "已迁移值发生变化，请先检查恢复状态" }
                continue
            }
            var target = SecureStorage.getStringStrict(destinationKey)
            if (target == null) {
                val value = nativeRead(box, key)
                    ?: error("旧存储键在快照中消失；已停止迁移以保护账号")
                SecureStorage.putStringStrict(destinationKey, value)
                target = SecureStorage.getStringStrict(destinationKey)
                check(target == value) { "加密写入验证失败；旧值仍保留，请重试" }
            }
            entry.put("status", "committed").put("targetSha256", sha256(checkNotNull(target).toByteArray()))
            persist()
        }
        // Archive the untouched source rather than writable mmap snapshots.
        verifySource()
        MigrationArchive.create(archive, source, files())
        MigrationArchive.verify(archive, files())
        verifyTargets()
        state.put("phase", "archived")
        persist()
        cleanPlaintext()
        complete()
    }

    private fun prepareSnapshot() {
        val entries = source.listFiles()?.sortedBy { it.name } ?: error("无法读取旧存储目录")
        require(entries.size <= 4096) { "旧存储文件数超出迁移限制" }
        val staging = File(root, "snapshot-copying-${UUID.randomUUID()}").apply { check(mkdir()) }
        val manifest = JSONArray()
        var total = 0L
        entries.forEach { original ->
            require(original.isFile && !Files.isSymbolicLink(original.toPath()) && original.canonicalFile.parentFile == source) { "旧存储存在非普通文件，已暂停迁移" }
            total += original.length()
            require(total <= 512L * 1024 * 1024) { "旧存储超过 512 MiB，请先执行人工恢复" }
            val copy = File(staging, original.name)
            FileOutputStream(copy).use { output -> original.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            val digest = sha256(copy)
            check(digest == sha256(original)) { "复制时旧存储仍在变化，请关闭旧进程后重试" }
            manifest.put(JSONObject().put("name", original.name).put("size", copy.length()).put("sha256", digest))
        }
        if (snapshot.exists()) {
            check(snapshot.renameTo(File(root, "incomplete-${UUID.randomUUID()}"))) { "无法保留未完成的旧快照" }
        }
        check(staging.renameTo(snapshot)) { "无法提交迁移快照" }
        val response = JSONObject(StorageNative.listKeys(snapshot.absolutePath))
        check(!response.has("error")) { response.optString("error", "无法读取旧存储索引") }
        val keys = response.getJSONArray("keys")
        state = JSONObject().put("schemaVersion", 1).put("phase", "copy_complete")
            .put("source", source.absolutePath).put("files", manifest).put("keys", keys)
        persist()
    }

    private fun nativeRead(box: String, key: String): String? {
        val request = JSONArray().put(JSONObject().put("box", box).put("key", key))
        val response = JSONObject(StorageNative.readStrings(snapshot.absolutePath, request.toString()))
        check(!response.has("error")) { response.optString("error", "旧存储读取失败") }
        val values = response.getJSONArray("values")
        check(values.length() == 1) { "旧存储返回了不完整的迁移结果" }
        val result = values.getJSONObject(0)
        check(result.getString("box") == box && result.getString("key") == key)
        return if (result.isNull("value")) null else result.getString("value")
    }

    private fun files(): List<MigrationFile> = state.getJSONArray("files").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).let { value ->
            MigrationFile(value.getString("name"), value.getLong("size"), value.getString("sha256"))
        } }
    }

    private fun verifySource() {
        val expected = files()
        check(source.listFiles()?.map { it.name }?.toSet().orEmpty() == expected.map { it.name }.toSet()) { "旧存储目录在迁移期间发生变化" }
        expected.forEach { item ->
            val file = File(source, item.name)
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == item.size && sha256(file) == item.sha256) { "旧存储源文件发生变化，已保留原数据" }
        }
    }

    private fun cleanPlaintext() {
        val expected = files().associateBy { it.name }
        // Missing files are allowed when resuming a crash during cleanup; changed/new files are not.
        source.listFiles().orEmpty().forEach { file ->
            val item = expected[file.name] ?: error("旧目录出现新数据，不能自动清理")
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == source && sha256(file) == item.sha256) { "旧文件发生变化，不能自动清理" }
            check(file.delete()) { "加密归档已验证，但原明文清理失败，请重试" }
        }
        if (source.exists()) check(source.delete()) { "无法清理已迁移的旧目录" }
        root.listFiles().orEmpty().filter { it.isDirectory && (it.name == "snapshot" || it.name.startsWith("snapshot-copying-") || it.name.startsWith("incomplete-")) }.forEach { directory ->
            check(directory.canonicalFile.parentFile == root && !Files.isSymbolicLink(directory.toPath()))
            directory.listFiles().orEmpty().forEach { file ->
                check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && !Files.isSymbolicLink(file.toPath()))
                check(file.delete()) { "无法清理临时明文快照" }
            }
            check(directory.delete())
        }
    }

    private fun complete() {
        state.put("phase", "complete").put("completedAt", System.currentTimeMillis())
        persist()
    }
    private fun verifyTargets() {
        val entries = state.getJSONArray("keys")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val value = SecureStorage.getStringStrict(entry.getString("box") + "." + entry.getString("key"))
                ?: error("已提交的加密迁移值缺失，请先从归档恢复")
            check(sha256(value.toByteArray()) == entry.getString("targetSha256")) { "迁移目标发生变化，已暂停明文清理" }
        }
    }
    private fun readState(): JSONObject = if (stateFile.baseFile.exists() || File(stateFile.baseFile.path + ".bak").exists())
        JSONObject(stateFile.openRead().bufferedReader().use { it.readText() }) else JSONObject()
    private fun persist() {
        val output = stateFile.startWrite()
        try { output.write(state.toString().toByteArray(Charsets.UTF_8)); stateFile.finishWrite(output) }
        catch (error: Throwable) { stateFile.failWrite(output); throw error }
    }
    private fun entry(box: String, key: String): JSONObject? = state.optJSONArray("keys")?.let { entries ->
        (0 until entries.length()).map(entries::getJSONObject).firstOrNull { it.getString("box") == box && it.getString("key") == key }
    }
    @Synchronized override fun get(box: String, key: String): String? {
        val item = entry(box, key) ?: return null
        return if (item.optString("status") in setOf("committed", "deleted")) null else nativeRead(box, key)
    }
    @Synchronized override fun remove(box: String, key: String) {
        val item = entry(box, key) ?: return
        // Core calls this after a verified secure write, or after an explicit secure deletion.
        val target = SecureStorage.getStringStrict("$box.$key")
        item.put("status", if (target == null) "deleted" else "committed")
        if (target != null) item.put("targetSha256", sha256(target.toByteArray()))
        persist()
    }
    @Synchronized override fun clearBox(box: String) {
        val entries = state.optJSONArray("keys") ?: return
        for (index in 0 until entries.length()) entries.getJSONObject(index).takeIf { it.getString("box") == box }?.put("status", "deleted")
        persist()
    }
}

internal data class MigrationFile(val name: String, val size: Long, val sha256: String)
internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream -> val buffer = ByteArray(64 * 1024); while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
