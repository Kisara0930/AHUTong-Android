package com.ahu.ahutong.ui.plugin

import android.app.Activity
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalInterfaceIntegrationTest {
    @Test fun firstLoginUsesOriginalPresentationAndPrivateMascotResources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".pluginpreview"))
        PluginPrivacyConsent.accept()
        AhupGate.initializeBootstrap(context).getOrThrow()
        AhupGate.completeInitialization(context)
        val resources = RuntimePluginLoader.resourceContext(context, "account")
        listOf("emoji_username", "emoji_password", "emoji_left_hand", "emoji_right_hand").forEach { name ->
            val id = resources.resources.getIdentifier(name, "mipmap", "com.ahu.ahutong.plugin.account")
            assertEquals(0x80, id ushr 24)
            assertTrue(requireNotNull(resources.getDrawable(id)).intrinsicWidth > 0)
        }
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<Activity>(intent).use {
            var texts = emptyList<String>()
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                texts = textTree(instrumentation.uiAutomation.rootInActiveWindow)
                if (texts.any { it.contains("学号") } && texts.any { it.contains("智慧安大") }) break
                Thread.sleep(100)
            }
            assertTrue(texts.toString(), texts.any { it.contains("学号") })
            assertTrue(texts.toString(), texts.any { it.contains("智慧安大") })
            assertFalse(texts.toString(), texts.any { it == "返回安大通" || it.contains("只启用你需要") })
        }
    }

    private fun textTree(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val values = mutableListOf<String>()
        node.text?.toString()?.let(values::add)
        for (index in 0 until node.childCount) values += textTree(node.getChild(index))
        node.recycle()
        return values
    }
}
