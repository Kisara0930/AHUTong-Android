package com.ahu.ahutong.ui.plugin

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run once with ahupColdStartPhase=prepare, force-stop the preview app, then run with phase=verify. */
@RunWith(AndroidJUnit4::class)
class PluginColdStartIntegrationTest {
    @Test fun signedPackagesSurviveArtProfilesAndARealProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("ahupColdStartPhase")
        assumeTrue("Requires two explicit instrumentation invocations", phase == "prepare" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".pluginpreview")) { "Cold-start fixtures must use the isolated preview app" }
        val marker = File(context.filesDir, "ahup-cold-start-fixture.txt")
        // This verification deliberately runs before bootstrap can repair even a mandatory package.
        val installed = AhupInstaller.installedIds(context).map { AhupInstaller.verifyInstalled(context, it) }
        assertTrue("Install the complete signed delivery fixture first", installed.size >= 21)
        val versions = installed.map { "${it.id}:${it.versionCode}" }.sorted()
        if (phase == "verify") {
            assertTrue("Run prepare in a separate process first", marker.isFile)
            val previous = marker.readLines()
            assertNotEquals("The app must be force-stopped between phases", previous.first().toInt(), Process.myPid())
            assertEquals("No reinstall or package repair may occur between phases", previous.drop(1), versions)
        }
        AhupGate.initializeBootstrap(context).getOrThrow()
        installed.filter { AhupInstaller.isEnabled(context, it.id) }.forEach { manifest ->
            instrumentation.runOnMainSync {
                val instance = RuntimePluginLoader.ensureLoaded(context, manifest.id)
                assertTrue("${manifest.id} must execute from disposable code cache",
                    instance.javaClass.classLoader.toString().contains(File(context.codeCacheDir, "ahup-runtime").absolutePath))
            }
        }
        installed.forEach { AhupInstaller.verifyInstalled(context, it.id) }
        if (phase == "prepare") marker.writeText((listOf(Process.myPid().toString()) + versions).joinToString("\n"))
    }
}
