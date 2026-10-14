package com.ahu.ahutong.plugin.electricity

import com.ahu.ahutong.data.model.ElectricityChargeInfo
import com.ahu.ahutong.data.model.ElectricityController
import com.ahu.ahutong.data.model.ElectricityDepositHistoryItem
import com.ahu.ahutong.data.model.RoomSelectionInfo
import com.ahu.ahutong.data.security.SecureBoxStore
import com.ahu.ahutong.data.session.AccountGeneration
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Preserve existing encrypted per-account keys while removing the app-wide cache dependency. */
class ElectricityCache(profile: String) {
    private val box = SecureBoxStore.userBox(profile)
    private val generation = AccountGeneration.current
    private val gson = Gson()
    private fun get(key: String): String? { AccountGeneration.requireCurrent(generation); return SecureBoxStore.get(box, key) }
    private fun put(key: String, value: String) { AccountGeneration.requireCurrent(generation); SecureBoxStore.put(box, key, value) }
    fun getElectricityController(): ElectricityController = ElectricityController.entries
        .firstOrNull { it.name == get("electricity_controller") } ?: ElectricityController.C
    fun setElectricityController(value: ElectricityController) = put("electricity_controller", value.name)
    fun getRoomSelection(): RoomSelectionInfo? = get("room_selection_info")?.let {
        runCatching { gson.fromJson(it, RoomSelectionInfo::class.java) }.getOrNull()
    }
    fun saveRoomSelection(value: RoomSelectionInfo) = put("room_selection_info", gson.toJson(value))
    fun getElectricityDepositHistory(): List<ElectricityDepositHistoryItem> = get("electricity_room_history")?.let {
        runCatching { gson.fromJson<List<ElectricityDepositHistoryItem>>(it,
            object : TypeToken<List<ElectricityDepositHistoryItem>>() {}.type) }.getOrNull()
    }.orEmpty()
    fun saveElectricityDepositHistory(value: List<ElectricityDepositHistoryItem>) = put("electricity_room_history", gson.toJson(value))
    fun getElectricityChargeInfo(): ElectricityChargeInfo? = get("electricity_charge_acl")?.let {
        runCatching { gson.fromJson(it, ElectricityChargeInfo::class.java) }.getOrNull()
    }
    fun saveElectricityChargeInfo(value: ElectricityChargeInfo) = put("electricity_charge_acl", gson.toJson(value))
}
