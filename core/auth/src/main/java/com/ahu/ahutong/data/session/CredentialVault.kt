package com.ahu.ahutong.data.session

/**
 * 凭据读写的唯一入口（ADR 0002 / 0003）。
 *
 * 密码属于「丢了就要重新登录」的一类数据：读取失败只能当作没有，绝不能降级成明文或缓存命中。
 * 实现是 :app 的 SecureCredentialVault（AES-GCM + Android Keystore，旧数据走 SecureBoxStore 的迁移链）。
 */
interface CredentialVault {

    fun saveWisdomPassword(password: String)

    fun wisdomPassword(): String?

    fun clearWisdomPassword()

    /**
     * 自定义校园卡 API 的密码（与统一认证密码是两回事，不能混用）。
     * 给默认空实现，保证既有 fake 不被破坏；生产实现是 :app 的 SecureCredentialVault。
     */
    fun saveCampusCardPassword(password: String) {}

    fun campusCardPassword(): String? = null

    fun clearCampusCardPassword() {}
}

