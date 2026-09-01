package com.webmediacapture.extractor

import android.content.Context
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.NetworkProbe

class ExtractorEngine(
    private val direct: DirectMediaExtractor = DirectMediaExtractor(),
    private val hls: HlsExtractor,
    private val dash: DashExtractor,
    private val ytDlp: YtDlpExtractor,
) {
    constructor(context: Context, probe: NetworkProbe) : this(
        hls = HlsExtractor(probe),
        dash = DashExtractor(probe),
        ytDlp = YtDlpExtractor(context),
    )

    suspend fun enrich(candidate: MediaCandidate): MediaCandidate = when (candidate.type) {
        MediaType.DIRECT, MediaType.AUDIO -> direct.extract(candidate)
        MediaType.HLS -> hls.extract(candidate)
        MediaType.DASH -> dash.extract(candidate)
        else -> candidate
    }

    suspend fun extractPage(pageSessionId: String, pageUrl: String, requestContext: RequestContext): MediaCandidate =
        ytDlp.extract(pageSessionId, pageUrl, requestContext)
}
