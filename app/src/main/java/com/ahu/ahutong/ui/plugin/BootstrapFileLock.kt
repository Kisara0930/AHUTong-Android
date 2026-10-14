package com.ahu.ahutong.ui.plugin

import java.io.File
import java.io.RandomAccessFile

/** Independent of state.lock: bootstrap may invoke the installer while this lock is held. */
internal object BootstrapFileLock {
    private val processMutex = Any()

    fun isHeldByCurrentThread(): Boolean = Thread.holdsLock(processMutex)

    fun <T> withLock(directory: File, block: () -> T): T {
        check(!isHeldByCurrentThread()) { "插件初始化不能重入" }
        return synchronized(processMutex) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建插件初始化目录" }
            RandomAccessFile(File(directory, "bootstrap.lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }
    }
}
