package com.ahu.ahutong.plugin.account

import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.plugin.account.AHUCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor

object CookieManager {
    private val encryptedPersistor = EncryptedCookiePersistor().also { encrypted ->
        // One-time, destructive migration: plaintext cookies must not remain on disk.
        val legacy = SharedPrefsCookiePersistor(AppEnvironmentHolder.context())
        val legacyCookies = legacy.loadAll()
        if (legacyCookies.isNotEmpty()) encrypted.saveAll(legacyCookies)
        legacy.clear()
    }

    val cookieJar = AHUCookieJar(SetCookieCache(), encryptedPersistor)

    /** Replies from an old account cannot insert cookies after a switch. */
    fun forGeneration(generation: Long): okhttp3.CookieJar = GenerationCookieJar(cookieJar, generation)

}
