package com.ahu.ahutong

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ahu.ahutong.core.common.AppEnvironmentHolder
import com.ahu.ahutong.core.common.UserNotice
import com.ahu.ahutong.data.toAhuError
import com.ahu.ahutong.core.common.toUserMessage

/**
 * 生产实现：把异常交给统一错误模型翻译成用户文案，再在主线程弹 Toast。
 * 文案与线程都在这里，核心模块只上报异常（ADR 0001 规则 3）。
 */
object ToastUserNotice : UserNotice {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun showFailure(error: Throwable) {
        val message = error.toAhuError().toUserMessage()
        mainHandler.post {
            Toast.makeText(AppEnvironmentHolder.context(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
