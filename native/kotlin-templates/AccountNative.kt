package com.ahu.ahutong.sdk.nativebridge

/** Copy into the account plugin; load its absolute SO path from this class loader. */
object AccountNative {
    @JvmStatic fun load(absolutePath: String) = System.load(absolutePath)
    @JvmStatic external fun login(username: String, password: String, cookies: String): String
}
