package com.ahu.ahutong.data.security

/**
 * Optional reader installed by the legacy-storage migration component before
 * account state is read. Ordinary secure storage never loads a campus SDK.
 *
 * The migration provider reads a snapshot. Removal records consumption in that
 * snapshot; the original source is retained until the migration is committed.
 */
object LegacyNativeStorage {
    interface Reader {
        fun get(box: String, key: String): String?
        fun remove(box: String, key: String)
        fun clearBox(box: String)
    }

    @Volatile private var reader: Reader? = null

    fun install(value: Reader) { reader = value }
    fun get(box: String, key: String): String? = reader?.get(box, key)
    fun remove(box: String, key: String) { reader?.remove(box, key) }
    fun clearBox(box: String) { reader?.clearBox(box) }
}
