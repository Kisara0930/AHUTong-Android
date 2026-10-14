package com.ahu.ahutong.sdk.nativebridge

object CampusCardNative {
    @JvmStatic fun load(absolutePath: String) = System.load(absolutePath)
    @JvmStatic external fun getBalance(cookies: String): String
    @JvmStatic external fun getQrcode(cookies: String): String
    @JvmStatic external fun refreshToken(cookies: String): String
}
