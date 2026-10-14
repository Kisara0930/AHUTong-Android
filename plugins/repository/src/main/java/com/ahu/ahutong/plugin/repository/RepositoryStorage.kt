package com.ahu.ahutong.plugin.repository

import com.ahu.ahutong.core.plugin.PluginStorage

/** Versioned repository cache keys retain their original names for migration. */
internal class RepositoryStorage(private val storage: PluginStorage) {
    fun decodeString(key: String, default: String? = null): String? = storage.getString(key) ?: default
    fun decodeLong(key: String, default: Long): Long = storage.getLong(key) ?: default
    fun decodeInt(key: String, default: Int): Int = storage.getLong(key)?.toInt() ?: default
    fun encode(key: String, value: String): Boolean { storage.putString(key, value); return true }
    fun encode(key: String, value: Long): Boolean { storage.putLong(key, value); return true }
    fun encode(key: String, value: Int): Boolean = encode(key, value.toLong())
}
