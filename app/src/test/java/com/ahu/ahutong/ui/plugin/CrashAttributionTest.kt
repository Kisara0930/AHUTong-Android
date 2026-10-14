package com.ahu.ahutong.ui.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrashAttributionTest {
    private val account = CrashPlugin("account", 3, "example.account", listOf("libauth.so"))
    private val schedule = CrashPlugin("schedule", 5, "example.schedule", listOf("libschedule.so"))

    @Test fun usesTheOriginatingPluginFrameInsteadOfTheVisiblePage() {
        assertEquals(account, CrashAttribution.javaOwner(listOf("java.lang.Thread", "example.account.Refresh", "example.schedule.Screen"), listOf(account, schedule)))
    }
    @Test fun sharedRuntimeAndUnrelatedFailuresAreNotAssignedToAPlugin() {
        assertNull(CrashAttribution.javaOwner(listOf("androidx.compose.SomeClass", "example.accounting.Other"), listOf(account)))
    }
    @Test fun nativeAttributionRequiresOneUniqueLibraryOwner() {
        assertEquals(schedule, CrashAttribution.nativeOwner("#00 pc 0123 /data/user/0/app/libschedule.so", listOf(account, schedule)))
        assertNull(CrashAttribution.nativeOwner("libauth.so libschedule.so", listOf(account, schedule)))
        assertNull(CrashAttribution.nativeOwner("", listOf(account, schedule)))
    }
}
