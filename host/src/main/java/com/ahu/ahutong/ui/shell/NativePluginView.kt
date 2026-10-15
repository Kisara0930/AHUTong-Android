package com.ahu.ahutong.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.ahu.ahutong.runtime.KernelBindings

/** Feature entry crosses the native View ABI; UI runtime supplies lifecycle/navigation/theming. */
@Composable
internal fun NativePluginView(id: String, context: Context, onRepair: () -> Unit) {
    val parent = rememberCompositionContext()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            var base: Context? = context
            while (base is ContextWrapper && base !is Activity) base = base.baseContext
            val activity = base as? Activity ?: error("缺少页面容器")
            try {
                KernelBindings.requireEnvironment().component(id).plugin.createView(activity, Bundle()).also { view ->
                    if (view is ComposeView) view.setParentCompositionContext(parent)
                }
            } catch (error: Throwable) {
                android.util.Log.e("NativePluginView", "$id 页面加载失败", error)
                TextView(activity).apply {
                    text = "组件页面无法加载：${error.message}\n点击打开修复设置"
                    setOnClickListener { onRepair() }
                }
            }
        },
        onRelease = { view -> if (view is ComposeView) view.disposeComposition() },
        update = { view -> if (view is ComposeView) view.setParentCompositionContext(parent) }
    )
}
