package com.ahu.ahutong.ui.shell

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ahu.ahutong.data.session.SessionStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/** Generic, local UI metadata published through the existing plugin storage contract. */
@Composable
internal fun pluginPresentation(id: String): JsonObject? {
    val context = LocalContext.current.applicationContext
    val preferences = remember(id) { context.getSharedPreferences("plugin_$id", Context.MODE_PRIVATE) }
    var raw by remember(preferences) { mutableStateOf(preferences.getString("ui.presentation", null)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "ui.presentation") raw = preferences.getString(key, null)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(raw) { runCatching { raw?.takeIf { it.length < 16_384 }?.let { JsonParser.parseString(it).asJsonObject } }.getOrNull() }
}

internal fun currentUiAccountKey(): String = SessionStore.currentUser()?.xh?.let { id ->
    MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}.orEmpty()
