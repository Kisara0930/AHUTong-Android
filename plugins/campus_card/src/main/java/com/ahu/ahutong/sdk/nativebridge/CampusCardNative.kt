package com.ahu.ahutong.sdk.nativebridge

object CampusCardNative {
    private val loaded by lazy { System.loadLibrary("ahup_campus_card"); true }
    fun ensureLoaded() { check(loaded) }
    @JvmStatic fun load(absolutePath: String) = System.load(absolutePath)
    @JvmStatic external fun getBalance(cookies: String): String
    @JvmStatic external fun getQrcode(cookies: String): String
    @JvmStatic external fun refreshToken(cookies: String): String
}
