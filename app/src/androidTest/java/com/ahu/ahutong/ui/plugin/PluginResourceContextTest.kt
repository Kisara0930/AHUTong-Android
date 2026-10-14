package com.ahu.ahutong.ui.plugin

import android.app.Activity
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginResourceContextTest {
    @Test fun configurationOverridesKeepActivityIdentityAndSharedResourceAccess() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(target.packageManager.getLaunchIntentForPackage(target.packageName))
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val resources = PluginResources(activity.resources, activity.resources, emptyList())
                val wrapped = PluginResourceContext(activity, resources, activity.classLoader)
                val configured = wrapped.createConfigurationContext(Configuration().apply { fontScale = 1.25f })
                assertTrue(configured is PluginResourceContext)
                assertSame(activity, (configured as PluginResourceContext).baseContext)
                assertSame(activity.applicationContext, configured.applicationContext)
                assertSame(activity.classLoader, configured.classLoader)
                assertEquals(activity.packageName, configured.packageName)
                assertEquals(1.25f, configured.resources.configuration.fontScale, 0.01f)
                assertEquals(activity.getString(android.R.string.ok), configured.getString(android.R.string.ok))
                assertTrue(configured.resources is PluginResources)
            }
        }
    }
}
