package com.ahu.ahutong.data.session

import com.ahu.ahutong.data.security.SecureBoxStore

/**
 * [CredentialVault] 的生产实现：底层固定为 SecureStorage（AES-GCM + Android Keystore），
 * 旧数据按 SecureBoxStore 的迁移链读取。
 *
 * 实现留在 :app，因为迁移链要用 Rust SDK 与 MMKV——接口因此不必认识设备侧的任何东西。
 */
object SecureCredentialVault : CredentialVault {

    private const val KEY = "password_wisdom"
    private const val CAMPUS_CARD_KEY = "password_campus_card"

    override fun saveWisdomPassword(password: String) {
        if (password.isEmpty()) SecureBoxStore.remove(KEY) else SecureBoxStore.put(KEY, password)
    }

    override fun wisdomPassword(): String? =
        SecureBoxStore.getOrMigrate(KEY) { SecureBoxStore.legacyString(KEY) }

    override fun clearWisdomPassword() = SecureBoxStore.remove(KEY)

    override fun saveCampusCardPassword(password: String) {
        if (password.isEmpty()) {
            SecureBoxStore.remove(CAMPUS_CARD_KEY)
        } else {
            SecureBoxStore.put(CAMPUS_CARD_KEY, password)
        }
    }

    override fun campusCardPassword(): String? = SecureBoxStore.get(CAMPUS_CARD_KEY)

    override fun clearCampusCardPassword() = SecureBoxStore.remove(CAMPUS_CARD_KEY)
}

