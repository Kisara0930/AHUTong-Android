package com.ahu.ahutong.ui.plugin

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ahu.ahutong.data.model.User
import com.ahu.ahutong.data.session.AhuSessionState
import com.ahu.ahutong.data.session.CampusSessionService
import com.ahu.ahutong.data.session.SessionStore
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run prepare, force-stop the isolated preview, then run verify. No campus request is made. */
@RunWith(AndroidJUnit4::class)
class RestoredAccountSessionIntegrationTest {
    @Test fun persistedIdentityRestoresAfterProcessRestartWithoutOverridingExpiry() {
        val phase = InstrumentationRegistry.getArguments().getString("sessionColdStartPhase")
        assumeTrue("Requires two explicit invocations", phase == "prepare" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".pluginpreview"))
        val marker = File(context.filesDir, "restored-session-fixture")
        val fixtureId = "plugin-session-fixture"
        PluginPrivacyConsent.accept()
        if (phase == "prepare") {
            check(SessionStore.currentUser() == null) { "Use an empty, disposable preview account" }
            AhupGate.initializeBootstrap(context).getOrThrow()
            RuntimePluginLoader.ensureLoaded(context, "account")
            val user = User().apply { xh = fixtureId; name = "Session fixture" }
            SessionStore.persistCurrentUser(user)
            assertEquals(AhuSessionState.Status.Anonymous, AhuSessionState.status.value)
            marker.writeText(Process.myPid().toString())
            return
        }

        assertTrue("Run prepare first", marker.isFile)
        assertNotEquals("Force-stop between phases", marker.readText().toInt(), Process.myPid())
        assertEquals(fixtureId, SessionStore.currentUser()?.xh)
        assertEquals("A fresh process has no in-memory authentication yet",
            AhuSessionState.Status.Anonymous, AhuSessionState.status.value)
        try {
            AhupGate.initializeBootstrap(context).getOrThrow()
            RuntimePluginLoader.ensureLoaded(context, "account")
            val service = requireNotNull(PluginServices.resolve(CampusSessionService::class.java))
            assertEquals(AhuSessionState.Status.Authenticated, service.state.value)
            val constructor = service.javaClass.getDeclaredConstructor()

            AhuSessionState.markExpired()
            constructor.newInstance()
            assertEquals("Cached identity cannot clear explicit expiry",
                AhuSessionState.Status.Expired, service.state.value)

            SessionStore.clearPersistedCurrentUser()
            AhuSessionState.markAnonymous()
            constructor.newInstance()
            assertEquals("No stored identity cannot authenticate",
                AhuSessionState.Status.Anonymous, service.state.value)
        } finally {
            if (SessionStore.currentUser()?.xh == fixtureId) SessionStore.clearPersistedCurrentUser()
            AhuSessionState.markAnonymous()
            marker.delete()
        }
    }
}
