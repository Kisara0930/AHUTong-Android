package com.ahu.ahutong.plugin.free_classroom.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals

class FreeClassroomQueryPlanningTest {
    @Test
    fun `all buildings uses one backend query`() {
        assertEquals(listOf(""), freeClassroomBuildingQueries(emptySet()))
    }

    @Test
    fun `selected buildings and units are normalized`() {
        assertEquals(listOf("2", "9"), freeClassroomBuildingQueries(setOf(9, 2)))
        assertEquals(listOf("1", "5", "13"), freeClassroomUnits(setOf(13, 5, 1, 99)))
    }

    @Test
    fun `no units means all thirteen periods`() {
        assertEquals((1..13).map(Int::toString), freeClassroomUnits(emptySet()))
    }
}
