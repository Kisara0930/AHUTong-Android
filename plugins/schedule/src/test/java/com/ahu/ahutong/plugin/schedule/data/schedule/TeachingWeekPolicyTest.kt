package com.ahu.ahutong.plugin.schedule.data.schedule

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeachingWeekPolicyTest {

    @Test
    fun `in-semester response infers term start from current teaching week`() {
        val position = TeachingWeekPolicy.resolveRemotePosition(
            now = LocalDate.of(2026, 3, 18),
            remoteWeekIndex = 3,
            remoteIsInSemester = true,
            cachedStartDate = null,
            officialStartDate = null
        )

        assertTrue(position.isInSemester)
        assertEquals(3, position.week)
        assertEquals(LocalDate.of(2026, 3, 2), position.startDate)
    }

    @Test
    fun `holiday response uses official date and never becomes week one`() {
        val position = TeachingWeekPolicy.resolveRemotePosition(
            now = LocalDate.of(2026, 7, 21),
            remoteWeekIndex = 0,
            remoteIsInSemester = false,
            cachedStartDate = LocalDate.of(2026, 7, 20),
            officialStartDate = LocalDate.of(2026, 3, 2)
        )

        assertFalse(position.isInSemester)
        assertEquals(TeachingWeekPolicy.SCHEDULE_WEEK_COUNT, position.week)
        assertEquals(LocalDate.of(2026, 3, 2), position.startDate)
    }

    @Test
    fun `pre-semester holiday opens the first week with its real dates`() {
        val position = TeachingWeekPolicy.resolveRemotePosition(
            now = LocalDate.of(2026, 8, 15),
            remoteWeekIndex = 0,
            remoteIsInSemester = false,
            cachedStartDate = null,
            officialStartDate = LocalDate.of(2026, 9, 7)
        )

        assertFalse(position.isInSemester)
        assertEquals(1, position.week)
        assertEquals(LocalDate.of(2026, 9, 7), position.startDate)
    }

    @Test
    fun `stale holiday state expires when the semester begins`() {
        assertTrue(
            TeachingWeekPolicy.resolveLocalSemesterState(
                startDate = LocalDate.of(2026, 9, 7),
                date = LocalDate.of(2026, 9, 7),
                cachedState = false,
                stateObservedOn = LocalDate.of(2026, 9, 6)
            )
        )
    }

    @Test
    fun `stale in-semester state expires after the schedule range`() {
        assertFalse(
            TeachingWeekPolicy.resolveLocalSemesterState(
                startDate = LocalDate.of(2026, 3, 2),
                date = LocalDate.of(2026, 7, 20),
                cachedState = true,
                stateObservedOn = LocalDate.of(2026, 7, 19)
            )
        )
    }

    @Test
    fun `same-day remote holiday state remains authoritative`() {
        val date = LocalDate.of(2026, 10, 1)
        assertFalse(
            TeachingWeekPolicy.resolveLocalSemesterState(
                startDate = LocalDate.of(2026, 9, 7),
                date = date,
                cachedState = false,
                stateObservedOn = date
            )
        )
    }
}
