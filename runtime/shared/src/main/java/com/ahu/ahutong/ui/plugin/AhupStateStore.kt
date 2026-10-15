package com.ahu.ahutong.ui.plugin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@androidx.annotation.Keep
internal data class InstalledAhup(
    @SerializedName("activeVersion") val activeVersion: Int,
    @SerializedName("previousVersion") val previousVersion: Int? = null,
    @SerializedName("lastGoodVersion") val lastGoodVersion: Int? = null,
    @SerializedName("enabled") val enabled: Boolean = false
)

@androidx.annotation.Keep
internal data class AhupState(
    @SerializedName("installed") val installed: Map<String, InstalledAhup> = emptyMap(),
    @SerializedName("required") val required: Set<String> = setOf("account"),
    @SerializedName("initialized") val initialized: Boolean = false
)

/** One durable pointer commits an entire dependency set; version directories are immutable. */
internal class AhupStateStore(private val root: File) {
    private val gson = Gson()
    private val stateFile get() = File(root, "state.json")

    fun read(): AhupState = locked { readLocked() }
    fun <T> inspect(block: (AhupState) -> T): T = locked { block(readLocked()) }

    fun update(transform: (AhupState) -> AhupState): AhupState = locked {
        val state = transform(readLocked())
        validate(state)
        val temp = File(root, "state.pending")
        FileOutputStream(temp).use {
            it.write(gson.toJson(state).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        // Same-filesystem atomic rename never exposes a partially written selection.
        Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        state
    }

    private fun readLocked(): AhupState {
        if (!stateFile.exists()) return AhupState()
        return gson.fromJson(stateFile.readText(), AhupState::class.java).also(::validate)
    }

    private fun validate(state: AhupState) {
        state.installed.forEach { (id, value) ->
            AhupPackageVerifier.validateId(id)
            require(value.activeVersion > 0 && (value.previousVersion ?: 1) > 0 &&
                (value.lastGoodVersion ?: 1) > 0) { "插件安装状态损坏" }
        }
        state.required.forEach(AhupPackageVerifier::validateId)
    }

    private fun <T> locked(block: () -> T): T {
        com.ahu.ahutong.runtime.KernelBindings.environment?.let { native ->
            return native.stateTransaction(java.util.concurrent.Callable { block() })
        }
        return synchronized(PROCESS_LOCK) {
        check(root.isDirectory || root.mkdirs()) { "无法创建插件状态目录" }
        RandomAccessFile(File(root, "state.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }
    }

    companion object { private val PROCESS_LOCK = Any() }
}
