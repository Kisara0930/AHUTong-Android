package com.ahu.ahutong.runtime

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.ahu.ahutong.hostapi.HostEnvironment
import com.ahu.ahutong.hostapi.HostPlugin

/** Entry does not start business code or read credentials. */
class SharedRuntimeEntry : HostPlugin {
    override fun initialize(environment: HostEnvironment) = KernelBindings.install(environment)
    override fun createView(activity: Activity, request: Bundle): View = TextView(activity).apply {
        text = "共享运行时已安装"
    }
}
