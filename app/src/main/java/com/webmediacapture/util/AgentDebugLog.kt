package com.webmediacapture.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object AgentDebugLog {
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(800, TimeUnit.MILLISECONDS).readTimeout(800, TimeUnit.MILLISECONDS).build()
    }

    fun emit(hypothesisId: String, location: String, message: String, data: Map<String, Any?> = emptyMap()) {
        Thread {
            runCatching {
                val payload = JSONObject()
                    .put("sessionId", "080fc4")
                    .put("runId", "duration-filter")
                    .put("hypothesisId", hypothesisId)
                    .put("location", location)
                    .put("message", message)
                    .put("timestamp", System.currentTimeMillis())
                val body = JSONObject()
                data.forEach { (key, value) -> body.put(key, value ?: JSONObject.NULL) }
                payload.put("data", body)
                val request = Request.Builder()
                    .url("http://10.0.2.2:7680/ingest/6416787f-1b21-4352-95bc-3f6f4c08a3c6")
                    .header("Content-Type", "application/json")
                    .header("X-Debug-Session-Id", "080fc4")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
            }
        }.start()
    }

    fun safeUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.take(80)
        return "${uri.host.orEmpty()}${uri.path.orEmpty()}".take(160)
    }
}
