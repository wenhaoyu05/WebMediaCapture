package com.webmediacapture.detector

import com.webmediacapture.model.MediaType
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.NetworkProbe
import com.webmediacapture.util.SafeLog

class HeaderProbeDetector(private val probe: NetworkProbe) {
    data class ProbeClassification(
        val type: MediaType,
        val mimeType: String?,
        val finalUrl: String,
        val contentLength: Long?,
    )

    suspend fun classify(request: ObservedRequest): ProbeClassification? {
        val result = runCatching { probe.inspect(request.url, request.requestContext) }
            .onFailure { SafeLog.w("PROBE", "Probe failed ${request.url}: ${it.message}") }
            .getOrNull() ?: return null
        SafeLog.d("PROBE", "Content-Type=${result.mimeType} status=${result.statusCode}")
        val type = classifyBody(result.bodyPrefix) ?: MimeTypeDetector.classify(result.mimeType) ?: return null
        return ProbeClassification(type, result.mimeType, result.finalUrl, result.contentLength)
    }

    internal fun classifyBody(prefix: ByteArray): MediaType? {
        if (prefix.isEmpty()) return null
        val text = prefix.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("#EXTM3U")) return MediaType.HLS
        if (text.contains("<MPD", true) || text.contains(":MPD", true)) return MediaType.DASH
        if (hasFtyp(prefix)) return MediaType.DIRECT
        if (prefix.size >= 4 && prefix[0] == 0x1A.toByte() && prefix[1] == 0x45.toByte() &&
            prefix[2] == 0xDF.toByte() && prefix[3] == 0xA3.toByte()
        ) {
            return MediaType.DIRECT
        }
        return null
    }

    private fun hasFtyp(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
    }
}
