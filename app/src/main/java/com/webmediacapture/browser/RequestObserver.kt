package com.webmediacapture.browser

import com.webmediacapture.model.ObservedRequest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class RequestObserver {
    private val mutableRequests = MutableSharedFlow<ObservedRequest>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val requests: SharedFlow<ObservedRequest> = mutableRequests
    private val tsLogs = AtomicInteger(0)
    private val imageLogs = AtomicInteger(0)

    fun observe(request: ObservedRequest) {
        logPlaybackRequest(request.url)
        mutableRequests.tryEmit(request)
    }

    private fun logPlaybackRequest(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase()
        val file = path.substringAfterLast('/').take(48)
        val qKeys = uri.rawQuery?.split('&')?.joinToString(",") { it.substringBefore('=') }.orEmpty().take(80)
        when {
            host.contains("playrecord") -> {
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "J3",
                    "RequestObserver.kt:observe",
                    "pr",
                    mapOf("ext" to ext, "file" to file, "hasQ" to qKeys.isNotEmpty(), "qKeys" to qKeys),
                )
                // #endregion
            }
            ext == "m3u8" && !host.contains("playrecord") -> {
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "K2",
                    "RequestObserver.kt:observe",
                    "hls",
                    mapOf("url" to com.webmediacapture.util.AgentDebugLog.safeUrl(url)),
                )
                // #endregion
            }
            ext == "ts" || ext == "m4s" -> {
                if (tsLogs.incrementAndGet() <= 12) {
                    // #region agent log
                    com.webmediacapture.util.AgentDebugLog.emit(
                        "J1",
                        "RequestObserver.kt:observe",
                        "seg",
                        mapOf("url" to com.webmediacapture.util.AgentDebugLog.safeUrl(url)),
                    )
                    // #endregion
                }
            }
            host.contains("tiktokcdn") && ext == "image" -> {
                if (imageLogs.incrementAndGet() <= 3) {
                    // #region agent log
                    com.webmediacapture.util.AgentDebugLog.emit(
                        "J5",
                        "RequestObserver.kt:observe",
                        "adimg",
                        mapOf("host" to host.take(40)),
                    )
                    // #endregion
                }
            }
        }
    }
}
