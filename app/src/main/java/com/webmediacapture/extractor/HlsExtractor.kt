package com.webmediacapture.extractor

import com.webmediacapture.detector.HlsDetector
import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.NetworkProbe

class HlsExtractor(private val probe: NetworkProbe) {
    private val detector = HlsDetector(probe)

    suspend fun extract(candidate: MediaCandidate): MediaCandidate {
        val request = ObservedRequest(
            url = candidate.mediaUrl,
            method = "GET",
            mimeType = candidate.mimeType,
            requestContext = candidate.requestContext,
            pageUrl = candidate.pageUrl,
            pageSessionId = candidate.pageSessionId,
            source = candidate.source,
            title = candidate.title,
        )
        return detector.inspect(request, DetectionSource.HTTP_PROBE) ?: candidate
    }
}
