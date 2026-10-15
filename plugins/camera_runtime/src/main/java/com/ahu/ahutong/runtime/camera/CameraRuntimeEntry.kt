package com.ahu.ahutong.runtime.camera

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.ahu.ahutong.hostapi.HostEnvironment
import com.ahu.ahutong.hostapi.HostPlugin
import com.ahu.ahutong.ui.plugin.PluginCameraServiceImpl

class CameraRuntimeEntry : HostPlugin {
    override fun initialize(environment: HostEnvironment) { }
    override fun createView(activity: Activity, request: Bundle): View = PluginCameraServiceImpl(activity).createPreviewView()
}
