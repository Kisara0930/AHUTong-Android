package com.ahu.ahutong.plugin.lost_found

import com.ahu.ahutong.data.crawler.model.adwnh.*
import com.ahu.ahutong.data.security.LegacyNativeStorage
import com.ahu.ahutong.data.security.SecureBoxStore
import com.ahu.ahutong.data.security.SecureStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Copy legacy account caches into a plugin namespace; never delete the migration source. */
class LostFoundCache(accountId: String, private val guard: () -> Unit) {
    private val oldBox = SecureBoxStore.userBox(accountId)
    private val newPrefix = "plugin_lost_found.$oldBox."
    private val gson = Gson()
    private fun raw(key: String): String? {
        guard()
        SecureStorage.getString(newPrefix + key)?.let { return it }
        val old = SecureStorage.getString("$oldBox.$key") ?: LegacyNativeStorage.get(oldBox, key)
            ?: SecureBoxStore.legacyString(oldBox, key) ?: return null
        guard()
        SecureStorage.putString(newPrefix + key, old)
        return old
    }
    private fun save(key: String, value: Any) { guard(); SecureStorage.putString(newPrefix + key, gson.toJson(value)) }
    private fun <T> list(key: String, type: java.lang.reflect.Type): List<T> = runCatching { raw(key)?.let { gson.fromJson<List<T>>(it, type) } }.getOrNull().orEmpty()
    fun getLostFoundCampus(): List<CampusItem> = list("lost_found_campus", object : TypeToken<List<CampusItem>>() {}.type)
    fun saveLostFoundCampus(value: List<CampusItem>) = save("lost_found_campus", value)
    fun getLostFoundType(): List<LostFoundTypeItem> = list("lost_found_type", object : TypeToken<List<LostFoundTypeItem>>() {}.type)
    fun saveLostFoundType(value: List<LostFoundTypeItem>) = save("lost_found_type", value)
    fun getLostFoundList(state: Int): List<LostFoundItem> = list("lost_found_list_$state", object : TypeToken<List<LostFoundItem>>() {}.type)
    fun saveLostFoundList(state: Int, value: List<LostFoundItem>) = save("lost_found_list_$state", value)
    fun appendLostFoundList(state: Int, value: List<LostFoundItem>) = saveLostFoundList(state, (getLostFoundList(state) + value).distinctBy { it.id })
    fun clearLostFoundList(state: Int) { guard(); SecureStorage.putString(newPrefix + "lost_found_list_$state", "[]") }
}
