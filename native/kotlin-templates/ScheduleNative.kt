package com.ahu.ahutong.sdk.nativebridge

/** Copy into the schedule plugin; calls run off the Android main thread. */
object ScheduleNative {
    @JvmStatic fun load(absolutePath: String) = System.load(absolutePath)
    @JvmStatic external fun getSchedule(cookies: String): String
}
