package com.ahu.ahutong.ui.plugin

import android.app.Activity
import android.net.Uri

object PluginHostLauncherHub {
    @Volatile private var requestCameraPermissionImpl: (((Boolean) -> Unit) -> Unit)? = null
    @Volatile private var pickImageLauncherImpl: (((Uri?) -> Unit) -> Unit)? = null
    @Volatile var activity: Activity? = null

    fun attach(
        activity: Activity,
        requestCameraPermission: (onResult: (granted: Boolean) -> Unit) -> Unit,
        pickImageLauncher: (onResult: (uri: Uri?) -> Unit) -> Unit
    ) {
        this.activity = activity
        this.requestCameraPermissionImpl = requestCameraPermission
        this.pickImageLauncherImpl = pickImageLauncher
    }

    fun detach() {
        requestCameraPermissionImpl = null
        pickImageLauncherImpl = null
        activity = null
    }

    fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
        val impl = requestCameraPermissionImpl
            ?: throw IllegalStateException("宿主相机权限通道未初始化（Activity 已销毁？）")
        impl(onResult)
    }

    fun pickImage(onResult: (uri: Uri?) -> Unit) {
        val impl = pickImageLauncherImpl
            ?: throw IllegalStateException("宿主照片选择器通道未初始化（Activity 已销毁？）")
        impl(onResult)
    }
}
