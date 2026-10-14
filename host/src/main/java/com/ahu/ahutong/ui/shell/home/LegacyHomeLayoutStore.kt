package com.ahu.ahutong.ui.shell.home

import com.ahu.ahutong.data.security.SecureBoxStore
import com.ahu.ahutong.data.session.SessionStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class HomeWidgetLayoutFamily { CLASSIC, RADIANT }

/** The visual shell retains the exact per-account layout keys from the original homepage. */
internal object LegacyHomeLayoutStore {
    private fun key(family: HomeWidgetLayoutFamily) = if (family == HomeWidgetLayoutFamily.CLASSIC) "home_widget_slots" else "home_widget_slots_radiant"
    private fun box() = SecureBoxStore.userBox(SessionStore.currentUser()?.xh)
    fun hasStoredHomeWidgetSlots(family: HomeWidgetLayoutFamily) = !SecureBoxStore.get(box(), key(family)).isNullOrBlank()
    fun getHomeWidgetSlots(family: HomeWidgetLayoutFamily): List<String?> {
        val defaults = if (family == HomeWidgetLayoutFamily.CLASSIC) HomeWidgetDefaults.classic else HomeWidgetDefaults.radiant
        val raw = SecureBoxStore.get(box(), key(family)) ?: return defaults
        return runCatching { Gson().fromJson<List<String?>>(raw, object : TypeToken<List<String?>>() {}.type) }.getOrNull() ?: defaults
    }
    fun saveHomeWidgetSlots(family: HomeWidgetLayoutFamily, slots: List<String?>) {
        SecureBoxStore.put(box(), key(family), Gson().toJson(slots))
    }
}
