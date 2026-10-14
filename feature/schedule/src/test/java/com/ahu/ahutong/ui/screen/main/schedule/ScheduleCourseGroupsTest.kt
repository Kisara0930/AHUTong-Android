package com.ahu.ahutong.ui.screen.main.schedule

import com.ahu.ahutong.data.model.Course
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleCourseGroupsTest {
    @Test
    fun `same slot courses are retained in one group`() {
        val first = course("数学")
        val second = course("英语")
        assertEquals(listOf(listOf(first, second)), weeklyCourseGroups(listOf(first, second), 1))
        assertTrue(hasCourseConflict(listOf(first, second)))
    }

    @Test
    fun `partial and transitive overlaps form one group regardless of input order`() {
        val first = course("数学", start = 1, length = 2)
        val second = course("英语", start = 2, length = 2)
        val third = course("物理", start = 3, length = 2)
        assertEquals(
            listOf(listOf(first, second, third)),
            weeklyCourseGroups(listOf(third, first, second), 1)
        )
    }

    @Test
    fun `adjacent periods and different days are separate groups`() {
        val first = course("数学")
        val adjacent = course("英语", start = 3)
        val nextDay = course("物理", day = 2)
        assertEquals(
            listOf(listOf(first), listOf(adjacent), listOf(nextDay)),
            weeklyCourseGroups(listOf(first, adjacent, nextDay), 1)
        )
        assertFalse(hasCourseConflict(listOf(first, adjacent, nextDay)))
    }

    @Test
    fun `different teaching weeks do not cause a conflict or join weekly groups`() {
        val oddWeeks = course("数学", weeks = listOf(1, 3))
        val evenWeeks = course("英语", weeks = listOf(2, 4))
        val courses = listOf(oddWeeks, evenWeeks)
        assertEquals(listOf(listOf(oddWeeks)), weeklyCourseGroups(courses, 1))
        assertEquals(listOf(listOf(evenWeeks)), weeklyCourseGroups(courses, 2))
        assertEquals(emptyList(), weeklyCourseGroups(courses, 5))
        assertFalse(hasCourseConflict(courses))
        assertTrue(hasCourseConflict(courses + course("物理", weeks = listOf(3))))
    }

    private fun course(
        title: String,
        day: Int = 1,
        start: Int = 1,
        length: Int = 2,
        weeks: List<Int> = listOf(1)
    ) = Course().apply {
        name = title
        setWeekday(day.toString())
        setStartTime(start.toString())
        setLength(length.toString())
        weekIndexes = weeks
    }
}
