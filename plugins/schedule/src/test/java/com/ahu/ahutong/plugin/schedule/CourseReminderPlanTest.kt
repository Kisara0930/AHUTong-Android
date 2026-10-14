package com.ahu.ahutong.plugin.schedule

import com.ahu.ahutong.data.model.Course
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CourseReminderPlanTest {
    private val termStart = LocalDate.parse("2026-09-07")

    private fun course(weekday: Int = 1, weeks: List<Int> = listOf(1, 2)) = Course().apply {
        setCourseId("math")
        setName("数学")
        setWeekday(weekday.toString())
        setStartTime("1")
        setLength("2")
        setWeekIndexes(weeks)
    }

    private fun plan(
        now: String,
        courses: List<Course> = listOf(course()),
        deliveredKeys: Set<String> = emptySet(),
        account: String = "student-a",
        semester: String = "2026-2027-1",
        todayInSemester: Boolean = true
    ) = CourseReminderPlan.build(account, semester, termStart, courses,
        LocalDateTime.parse(now), todayInSemester, deliveredKeys)

    @Test
    fun `late rescheduling retains the real start time until the course begins`() {
        val before = plan("2026-09-07T07:49").first()
        val atReminder = plan("2026-09-07T07:50").first()
        val late = plan("2026-09-07T07:55").first()
        assertEquals(LocalDateTime.parse("2026-09-07T07:50"), before.reminderAt)
        assertEquals(before.key, atReminder.key)
        assertEquals(before.key, late.key)
        assertEquals(LocalDateTime.parse("2026-09-07T08:00"), late.courseStart)
        assertEquals(LocalDateTime.parse("2026-09-14T08:00"), plan("2026-09-07T08:00").first().courseStart)
    }

    @Test
    fun `delivered occurrences are skipped across repeated scheduling but next week remains`() {
        val occurrence = plan("2026-09-07T07:55").first()
        val after = plan("2026-09-07T07:55", deliveredKeys = setOf(occurrence.key))
        assertEquals(1, after.size)
        assertEquals(LocalDateTime.parse("2026-09-14T08:00"), after.single().courseStart)
    }

    @Test
    fun `the week before semester never creates an early first-week reminder`() {
        for (daysBefore in 1L..7L) {
            val now = termStart.minusDays(daysBefore).atTime(7, 55)
            val result = CourseReminderPlan.build("student", "term", termStart,
                listOf(course(now.dayOfWeek.value, listOf(1))), now)
            assertTrue(result.all { !it.courseStart.toLocalDate().isBefore(termStart) })
        }
    }

    @Test
    fun `courses more than 21 days away and alternating weeks are scheduled directly`() {
        val future = plan("2026-10-05T07:00", listOf(course(weeks = listOf(9, 11))))
        assertEquals(listOf("2026-11-02T08:00", "2026-11-16T08:00"), future.map { it.courseStart.toString() })
    }

    @Test
    fun `duplicate rows are coalesced and deliveries are isolated by account and semester`() {
        val courses = listOf(course(), course())
        val first = plan("2026-09-07T07:55", courses)
        assertEquals(2, first.size)
        assertNotEquals(first.first().key, plan("2026-09-07T07:55", account = "student-b").first().key)
        assertNotEquals(first.first().key, plan("2026-09-07T07:55", semester = "other-term").first().key)
        assertFalse(first.first().key.contains("student-a"))
    }

    @Test
    fun `updating a room does not repeat an already delivered course`() {
        val original = plan("2026-09-07T07:55").first()
        val moved = course().apply { setLocation("新教室") }
        assertEquals(original.key, plan("2026-09-07T07:55", listOf(moved)).first().key)
    }

    @Test
    fun `invalid sections clock ranges and teaching weeks are ignored`() {
        val invalid = listOf(
            course().apply { setStartTime("99") },
            course().apply { setClockRange("25:00-26:00") },
            course().apply { setClockRange("08:00-07:00") },
            course(weekday = 0), course(weeks = listOf(0, -1, 21))
        )
        assertTrue(plan("2026-09-07T07:00", invalid).isEmpty())
    }

    @Test
    fun `a non-teaching day suppresses today's occurrence without losing a future week`() {
        val result = plan("2026-09-07T07:55", todayInSemester = false)
        assertEquals(LocalDateTime.parse("2026-09-14T08:00"), result.single().courseStart)
    }

    @Test
    fun `the final course does not reschedule after the semester's cached courses end`() {
        assertTrue(plan("2026-09-14T08:00").isEmpty())
    }
}
