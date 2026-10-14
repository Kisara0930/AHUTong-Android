package com.ahu.ahutong.sdk.nativebridge

object GradesNative {
    @JvmStatic fun load(absolutePath: String) = System.load(absolutePath)
    @JvmStatic external fun getGrade(cookies: String): String
    @JvmStatic external fun getExamInfo(cookies: String): String
}
