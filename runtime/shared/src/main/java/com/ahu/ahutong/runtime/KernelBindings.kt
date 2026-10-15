package com.ahu.ahutong.runtime

import com.ahu.ahutong.hostapi.HostEnvironment

/** Kept inside the downloadable SDK; the APK only knows the Java host API. */
object KernelBindings {
    @Volatile var environment: HostEnvironment? = null
        private set
    fun install(value: HostEnvironment) {
        check(environment == null || environment === value) { "一个进程只能绑定一个原生宿主" }
        environment = value
    }
    fun requireEnvironment(): HostEnvironment = requireNotNull(environment) { "原生宿主尚未绑定" }
}
