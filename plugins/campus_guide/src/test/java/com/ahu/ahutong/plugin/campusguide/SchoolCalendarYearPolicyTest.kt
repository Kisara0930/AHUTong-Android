package com.ahu.ahutong.plugin.campusguide

import org.junit.Assert.assertEquals
import org.junit.Test

class SchoolCalendarYearPolicyTest {
    @Test
    fun displayNameFormatsAcademicYearForSelection() {
        assertEquals("2025—2026 学年", SchoolCalendarYearPolicy.displayName("2025-2026"))
    }

    @Test
    fun normalizeKeepsOnlyValidExistingAcademicYearIdentifiers() {
        assertEquals(
            listOf("2026-2027", "2025-2026", "2024-2025"),
            SchoolCalendarYearPolicy.normalize(
                listOf(
                    "2025-2026",
                    "2026-2027",
                    "invalid",
                    "2025-2027",
                    "2024-2025",
                    "2026-2027"
                )
            )
        )
    }

    @Test
    fun selectPrefersCurrentThenServerLatestThenNewestAvailable() {
        val years = listOf("2026-2027", "2025-2026")

        assertEquals(
            "2025-2026",
            SchoolCalendarYearPolicy.select(years, "2026-2027", "2025-2026")
        )
        assertEquals(
            "2026-2027",
            SchoolCalendarYearPolicy.select(years, "2026-2027", null)
        )
        assertEquals(
            "2026-2027",
            SchoolCalendarYearPolicy.select(years, "2024-2025", null)
        )
        assertEquals(null, SchoolCalendarYearPolicy.select(emptyList(), null, null))
    }
}
