package com.ahu.ahutong.ui.plugin

import com.ahu.ahutong.data.security.SecureBoxStore

/** Shared by foreground onboarding and every background entry point; never repairs encrypted data. */
object PluginPrivacyConsent {
    const val POLICY_VERSION = 1

    fun isAccepted(): Boolean {
        val rawVersion = SecureBoxStore.getStrict("privacyPolicyVersion")
        val version = rawVersion?.let { requireNotNull(it.toIntOrNull()) { "本地隐私确认版本损坏，原始数据已保留" } }
        val rawAccepted = SecureBoxStore.getStrict("privacyAccepted")
        val accepted = rawAccepted?.let { requireNotNull(it.toBooleanStrictOrNull()) { "本地隐私确认记录损坏，原始数据已保留" } } ?: false
        return accepted && (if (version != null) version >= POLICY_VERSION else POLICY_VERSION == 1)
    }

    fun accept() {
        SecureBoxStore.putStrict("agreementAccepted", "true")
        SecureBoxStore.putStrict("businessAccepted", "true")
        SecureBoxStore.putStrict("privacyAccepted", "true")
        SecureBoxStore.putStrict("privacyPolicyVersion", POLICY_VERSION.toString())
    }
}
