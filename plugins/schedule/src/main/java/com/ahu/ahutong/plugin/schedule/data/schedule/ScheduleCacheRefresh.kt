package com.ahu.ahutong.plugin.schedule.data.schedule

import com.ahu.ahutong.data.model.Course

data class ScheduleRefreshResult(
    val schedule: List<Course>,
    val changed: Boolean,
    val fetchedAt: Long
)

/** 比较两次课表快照，决定要不要刷新界面（纯函数，见 ScheduleSnapshotComparatorTest）。 */
object ScheduleSnapshotComparator {
    fun hasChanged(cached: List<Course>?, latest: List<Course>): Boolean {
        if (cached == null) return true
        if (cached.size != latest.size) return true
        return cached.groupingBy { it }.eachCount() != latest.groupingBy { it }.eachCount()
    }
}
