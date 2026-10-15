package com.ahu.ahutong.runtime.content

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.ahu.ahutong.hostapi.HostEnvironment
import com.ahu.ahutong.hostapi.HostPlugin
import io.noties.markwon.Markwon

class ContentRuntimeEntry : HostPlugin {
    override fun initialize(environment: HostEnvironment) { }
    override fun createView(activity: Activity, request: Bundle): View = TextView(activity).apply {
        Markwon.builder(activity).build().setMarkdown(this, request.getString("markdown").orEmpty())
    }
}
