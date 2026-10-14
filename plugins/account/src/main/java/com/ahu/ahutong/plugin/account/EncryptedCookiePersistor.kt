package com.ahu.ahutong.plugin.account

import com.ahu.ahutong.data.security.SecureStorage
import com.franmontiel.persistentcookiejar.persistence.CookiePersistor
import com.franmontiel.persistentcookiejar.persistence.SerializableCookie
import java.security.MessageDigest
import okhttp3.Cookie

/** Persists cookie payloads as AES-GCM ciphertext instead of plaintext preferences. */
class EncryptedCookiePersistor : CookiePersistor {
    override fun loadAll(): List<Cookie> =
        SecureStorage.entries(COOKIE_PREFIX).values.mapNotNull { encoded ->
            runCatching { SerializableCookie().decode(encoded) }.getOrNull()
        }

    override fun saveAll(cookies: Collection<Cookie>) {
        cookies.forEach { cookie ->
            val encoded = SerializableCookie().encode(cookie) ?: return@forEach
            SecureStorage.putString(storageKey(cookie), encoded)
        }
    }

    override fun removeAll(cookies: Collection<Cookie>) {
        cookies.forEach { SecureStorage.remove(storageKey(it)) }
    }

    override fun clear() {
        SecureStorage.clearPrefix(COOKIE_PREFIX)
    }

    private fun storageKey(cookie: Cookie): String {
        val identity = buildString {
            append(if (cookie.secure) "https" else "http")
            append("://")
            append(cookie.domain)
            append(cookie.path)
            append('|')
            append(cookie.name)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return COOKIE_PREFIX + digest
    }

    private companion object {
        const val COOKIE_PREFIX = "cookies."
    }
}
