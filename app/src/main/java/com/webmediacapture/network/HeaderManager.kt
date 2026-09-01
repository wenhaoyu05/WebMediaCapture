package com.webmediacapture.network

import com.webmediacapture.model.RequestContext
import okhttp3.Request

object HeaderManager {
    fun downloadHeaders(context: RequestContext): Map<String, String> = context.downloadHeaders()

    fun persistable(context: RequestContext): Map<String, String> = context.downloadHeaders().filterKeys {
        !it.equals("Cookie", true) && !it.equals("Authorization", true)
    }

    fun apply(builder: Request.Builder, context: RequestContext): Request.Builder {
        downloadHeaders(context).forEach { (name, value) -> builder.header(name, value) }
        return builder
    }
}
