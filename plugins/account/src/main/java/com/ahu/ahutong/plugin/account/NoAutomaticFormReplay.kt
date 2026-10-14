package com.ahu.ahutong.plugin.account

import okhttp3.Interceptor
import okhttp3.RequestBody
import okio.BufferedSink

/** Disable transparent resends of submitted forms. An explicit 401 retry creates a new call. */
internal val noAutomaticFormReplay = Interceptor { chain ->
    val request = chain.request()
    val body = request.body
    val protected = if (body != null && request.method !in setOf("GET", "HEAD", "OPTIONS")) {
        request.newBuilder().method(request.method, object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }).build()
    } else request
    chain.proceed(protected)
}
