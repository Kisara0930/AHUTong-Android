package com.ahu.ahutong.plugin.schedule

import com.ahu.ahutong.data.model.Course
import com.ahu.ahutong.plugin.schedule.data.schedule.ScheduleSectionTimes
import com.ahu.ahutong.plugin.schedule.data.schedule.TeachingWeekPolicy
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Pure occurrence calculation, shared by normal scheduling and late delivery. */
internal object CourseReminderPlan {
    const val LEAD_MINUTES = 10L

    data class Occurrence(
        val key: String,
        val course: Course,
        val courseStart: LocalDateTime
    ) {
        val reminderAt: LocalDateTime = courseStart.minusMinutes(LEAD_MINUTES)
    }

    fun build(
        accountKey: String,
        semester: String,
        termStart: LocalDate,
        courses: List<Course>,
        now: LocalDateTime,
        todayInSemester: Boolean = true,
        deliveredKeys: Set<String> = emptySet()
    ): List<Occurrence> = courses.flatMap { course ->
        if (course.weekday !in 1..7 || course.name.isNullOrBlank()) return@flatMap emptyList()
        val range = ScheduleSectionTimes.getCourseTimeRangeInMinutes(course)
        if (range.isEmpty() || range.first !in 0 until 24 * 60 || range.last !in 0 until 24 * 60) {
            return@flatMap emptyList()
        }
        val startTime = LocalTime.of(range.first / 60, range.first % 60)
        course.weekIndexes.filter { it in 1..TeachingWeekPolicy.SCHEDULE_WEEK_COUNT }.distinct().mapNotNull { week ->
            val date = runCatching {
                termStart.plusWeeks(week.toLong() - 1)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(course.weekday)))
            }.getOrNull() ?: return@mapNotNull null
            if (date.isBefore(termStart) || (!todayInSemester && date == now.toLocalDate())) {
                return@mapNotNull null
            }
            val courseStart = date.atTime(startTime)
            if (!courseStart.isAfter(now)) return@mapNotNull null
            val key = occurrenceKey(accountKey, semester, course, courseStart)
            if (key in deliveredKeys) return@mapNotNull null
            Occurrence(key, course, courseStart)
        }
    }.distinctBy { it.key }.sortedWith(compareBy({ it.reminderAt }, { it.key }))

    private fun occurrenceKey(
        accountKey: String,
        semester: String,
        course: Course,
        courseStart: LocalDateTime
    ): String {
        val courseIdentity = course.courseId?.takeIf { it.isNotBlank() }
            ?: listOf(course.name.orEmpty(), course.teacher.orEmpty())
                .joinToString(separator = "") { "${it.length}:$it" }
        val identity = listOf(accountKey, semester, courseIdentity, courseStart.toString())
            .joinToString(separator = "") { "${it.length}:$it" }
        // Persist only a digest, never the account id or course details.
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
