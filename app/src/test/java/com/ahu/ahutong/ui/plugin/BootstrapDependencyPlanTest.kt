package com.ahu.ahutong.ui.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapDependencyPlanTest {
    private fun plugin(id: String, version: Int = 1, required: Boolean = false,
        dependencies: List<AhupDependency> = emptyList()) = AhupManifest(
        id, id, "", 0, version.toString(), "AHUTong", "example.$id.Plugin", "icon.png", emptySet(),
        version, 1, "1", 26, emptySet(), dependencies, emptyList(), required)

    @Test fun anUpgradeAddsItsEntireNewDependencyClosure() {
        val account = plugin("account", 2, true, listOf(AhupDependency("campus_runtime", 2)))
        val runtime = plugin("campus_runtime", 2, dependencies = listOf(AhupDependency("codec", 1)))
        val unused = plugin("unused", dependencies = listOf(AhupDependency("not_bundled", 1)))
        val plan = BootstrapDependencyPlan.resolve(listOf(plugin("account")),
            listOf(account, runtime, plugin("codec"), unused), setOf("account"))
        assertEquals(setOf("account", "campus_runtime", "codec"), plan.requiredIds)
        assertEquals(plan.requiredIds, plan.installIds)
        assertEquals(2, plan.selected.getValue("account").versionCode)
        assertFalse("unused" in plan.selected)
    }

    @Test fun anInstalledHigherVersionAndItsDependenciesAreNeverDowngraded() {
        val installed = listOf(plugin("account", 4, dependencies = listOf(AhupDependency("crypto", 3))),
            plugin("crypto", 5), plugin("schedule", dependencies = listOf(AhupDependency("account", 3))))
        val bundled = listOf(plugin("account", 2, true, listOf(AhupDependency("legacy", 1))), plugin("crypto", 3))
        val plan = BootstrapDependencyPlan.resolve(installed, bundled, setOf("account"))
        assertEquals(setOf("account", "crypto"), plan.requiredIds)
        assertTrue(plan.installIds.isEmpty())
        assertEquals(4, plan.selected.getValue("account").versionCode)
        assertEquals(5, plan.selected.getValue("crypto").versionCode)
        assertTrue("schedule" in plan.selected)
        assertFalse("legacy" in plan.requiredIds)
    }

    @Test fun anOptionalInstalledPluginsMaximumVersionCanBlockTheHostUpdate() {
        val installed = listOf(plugin("account"), plugin("schedule", dependencies = listOf(AhupDependency("account", 1, 1))))
        val error = assertFailsWith<IllegalArgumentException> {
            BootstrapDependencyPlan.resolve(installed, listOf(plugin("account", 2, true)), setOf("account"))
        }
        assertTrue(error.message.orEmpty().contains("schedule"))
        assertTrue(error.message.orEmpty().contains("先更新或卸载"))
        assertEquals(1, installed.first().versionCode)
    }

    @Test fun everyEmbeddedRequiredManifestIsARootEvenWhenNotNamedByTheCaller() {
        val plan = BootstrapDependencyPlan.resolve(emptyList(), listOf(plugin("account"),
            plugin("identity", required = true, dependencies = listOf(AhupDependency("crypto", 1))), plugin("crypto")), setOf("account"))
        assertEquals(setOf("account", "identity", "crypto"), plan.requiredIds)
        assertEquals(plan.requiredIds, plan.installIds)
    }

    @Test fun missingAndCyclicDependenciesAndDuplicateEmbeddedIdentitiesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapDependencyPlan.resolve(emptyList(), listOf(plugin("account", dependencies = listOf(AhupDependency("missing", 1)))), setOf("account"))
        }
        assertFailsWith<IllegalArgumentException> {
            BootstrapDependencyPlan.resolve(emptyList(), listOf(plugin("account", dependencies = listOf(AhupDependency("crypto", 1))),
                plugin("crypto", dependencies = listOf(AhupDependency("account", 1)))), setOf("account"))
        }
        assertFailsWith<IllegalArgumentException> {
            BootstrapDependencyPlan.resolve(emptyList(), listOf(plugin("account"), plugin("account", 2)), setOf("account"))
        }
    }
}
