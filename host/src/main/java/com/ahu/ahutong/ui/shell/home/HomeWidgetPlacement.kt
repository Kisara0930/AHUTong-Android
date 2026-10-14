package com.ahu.ahutong.ui.shell.home




import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shares external slot changes with the already composed Home screen. */
object HomeWidgetPlacement {
    const val NOTICE_WIDGET_ID = HomeWidgetDefaults.NOTICE_WIDGET_ID
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision

    fun currentSlots(): List<String?> {
        val family = HomeWidgetLayoutFamily.RADIANT
        val saved = if (LegacyHomeLayoutStore.hasStoredHomeWidgetSlots(family)) {
            LegacyHomeLayoutStore.getHomeWidgetSlots(family)
        } else {
            LegacyHomeLayoutStore.getHomeWidgetSlots(HomeWidgetLayoutFamily.CLASSIC)
        }
        val known = HomeWidgetRegistry.availableWidgets(true).mapTo(mutableSetOf()) { it.id }
        val seen = mutableSetOf<String>()
        return List(HomeWidgetRegistry.slotCountRadiant) { index ->
            saved.getOrNull(index)?.takeIf { it in known && seen.add(it) }
        }
    }

    @Synchronized
    fun placeNoticeAt(index: Int): Boolean {
        val slots = currentSlots()
        if (index !in slots.indices) return false
        if (NOTICE_WIDGET_ID in slots) return true
        val updated = slots.toMutableList().apply { this[index] = NOTICE_WIDGET_ID }
        LegacyHomeLayoutStore.saveHomeWidgetSlots(HomeWidgetLayoutFamily.RADIANT, updated)
        mutableRevision.value += 1
        return true
    }

    @Synchronized
    fun removeNotice(): Boolean {
        val slots = currentSlots()
        if (NOTICE_WIDGET_ID !in slots) return true
        LegacyHomeLayoutStore.saveHomeWidgetSlots(
            HomeWidgetLayoutFamily.RADIANT,
            slots.map { if (it == NOTICE_WIDGET_ID) null else it }
        )
        mutableRevision.value += 1
        return true
    }
}
