package com.ahu.ahutong

import android.content.Context
import com.ahu.ahutong.core.common.AppEnvironment

/**
 * Android 上的生产实现：Application 本身就是 Context。
 * 安装点在 AHUApplication.onCreate() 的第一行。
 */
class AndroidAppEnvironment(override val context: Context) : AppEnvironment
