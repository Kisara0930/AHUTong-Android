package com.ahu.ahutong.plugin.schedule.data.schedule

import com.ahu.ahutong.data.model.Course
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleTimeRangeTest {
    @Test
    fun `single section uses its exact clock range`() {
        val course = Course().apply {
            setStartTime("1")
            setLength("1")
        }

        assertEquals(8 * 60..8 * 60 + 45, ScheduleSectionTimes.getCourseTimeRangeInMinutes(course))
    }

    @Test
    fun `multi section course ends at the last section`() {
        val course = Course().apply {
            setStartTime("4")
            setLength("3")
        }

        assertEquals(
            10 * 60 + 40..14 * 60 + 45,
            ScheduleSectionTimes.getCourseTimeRangeInMinutes(course)
        )
    }

    @Test
    fun `malformed cached sections are ignored instead of crashing consumers`() {
        val malformed = Course().apply {
            setStartTime("not-a-section")
            setLength("2")
        }
        val outsideTimetable = Course().apply {
            setStartTime("13")
            setLength("2")
        }

        assertEquals(IntRange.EMPTY, ScheduleSectionTimes.getCourseTimeRangeInMinutes(malformed))
        assertEquals(IntRange.EMPTY, ScheduleSectionTimes.getCourseTimeRangeInMinutes(outsideTimetable))
        assertEquals(emptyList(), malformed.weekIndexes)
    }
}
