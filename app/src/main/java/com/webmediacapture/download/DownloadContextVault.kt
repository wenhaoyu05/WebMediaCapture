package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import java.util.concurrent.ConcurrentHashMap

object DownloadContextVault {
    private val values = ConcurrentHashMap<String, RequestContext>()
    fun put(id: String, context: RequestContext) { values[id] = context }
    fun get(id: String): RequestContext? = values[id]
    fun remove(id: String) { values.remove(id) }
}
