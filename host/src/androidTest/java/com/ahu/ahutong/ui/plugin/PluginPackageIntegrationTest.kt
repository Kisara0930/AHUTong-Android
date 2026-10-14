package com.ahu.ahutong.ui.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ahu.ahutong.core.plugin.AhuPlugin
import com.ahu.ahutong.data.session.CampusSessionService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the actual signed delivery archives, staged into the preview app's external files directory. */
@RunWith(AndroidJUnit4::class)
class PluginPackageIntegrationTest {
    @Test fun deliveredPackagesVerifyInstallAndLoadThroughTheSharedSdk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".pluginpreview")) { "Run integration fixtures only in the isolated preview application" }
        val expected = setOf("account", "appearance", "bathroom", "campus_card", "campus_circle", "campus_guide",
            "campus_notices", "chaoxing", "electricity", "evaluation", "free_classroom", "grade_exam",
            "lost_found", "network_recharge", "personalization", "program_completion", "repository", "schedule",
            "storage_migration", "student_mail", "weather")
        val directory = requireNotNull(context.getExternalFilesDir("test-plugins"))
        val archives = directory.listFiles().orEmpty().filter { it.extension == "ahup" }
        assertEquals("Stage all signed AHUP artifacts before running this integration test", expected, archives.map { it.nameWithoutExtension }.toSet())
        PluginPrivacyConsent.accept()
        AhupGate.initializeBootstrap(context).getOrThrow()
        val candidates = archives.map { file -> file.inputStream().use { AhupInstaller.inspect(context, it) } }
        try {
            val missing = candidates.filter { AhupInstaller.manifestOf(context, it.manifest.id) == null }
            if (missing.isNotEmpty()) AhupInstaller.installBatch(context, missing)
        } finally { candidates.forEach { it.close() } }
        AhupGate.completeInitialization(context, expected - "storage_migration")
        val failures = mutableListOf<String>()
        (listOf("account") + (expected - setOf("account", "storage_migration")).sorted()).forEach { id ->
            try {
                var plugin: AhuPlugin? = null
                instrumentation.runOnMainSync { plugin = RuntimePluginLoader.ensureLoaded(context, id) }
                assertEquals(id, plugin!!.meta.id)
                assertNotSame(context.classLoader, plugin!!.javaClass.classLoader)
                val resources = RuntimePluginLoader.resourceContext(context, id)
                assertEquals(context.packageName, resources.packageName)
                assertEquals(context.getString(android.R.string.ok), resources.getString(android.R.string.ok))
                if (id == "campus_card") {
                    // Resolve the real ARM64 JNI library through its owning plugin loader, without a network/payment call.
                    val bridge = plugin!!.javaClass.classLoader!!.loadClass("com.ahu.ahutong.sdk.nativebridge.CampusCardNative")
                    bridge.getMethod("ensureLoaded").invoke(bridge.getField("INSTANCE").get(null))
                }
                if (id == "schedule") {
                    val text = resources.resources.getIdentifier("current_week", "string", "com.ahu.ahutong.plugin.schedule")
                    val icon = resources.resources.getIdentifier("ic_aiming", "drawable", "com.ahu.ahutong.plugin.schedule")
                    assertEquals(0x81, text ushr 24)
                    assertEquals("当前周", resources.getString(text))
                    assertNotNull(resources.getDrawable(icon))
                    val largeFont = resources.createConfigurationContext(android.content.res.Configuration().apply { fontScale = 1.4f })
                    assertEquals("当前周", largeFont.getString(text))
                    assertNotNull(largeFont.getDrawable(icon))
                    val widget = (plugin as com.ahu.ahutong.core.plugin.PluginWidgetProvider)
                        .renderWidget(context, 42, android.os.Bundle())
                    instrumentation.runOnMainSync {
                        assertNotNull(widget.apply(context, android.widget.FrameLayout(context)))
                    }
                }
            } catch (failure: Throwable) {
                failures += "$id: ${failure.javaClass.simpleName}: ${failure.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertNotNull(PluginServices.resolve(CampusSessionService::class.java))
        assertFalse("Legacy storage migration must remain inactive for a fresh installation", AhupInstaller.isEnabled(context, "storage_migration"))
        assertTrue(AhupGate.isInitialized(context))
    }
}
