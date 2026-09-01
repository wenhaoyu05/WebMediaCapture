package com.webmediacapture.detector

import com.webmediacapture.model.MediaType
import java.net.URI

object MediaClassifier {
    fun fromUrl(url: String): MediaType? = UrlPatternDetector.classify(url)

    fun fromMime(mimeType: String?): MediaType? = MimeTypeDetector.classify(mimeType)

    fun needsProbe(url: String, mimeType: String?, acceptHeader: String? = null): Boolean {
        if (fromUrl(url) != null || fromMime(mimeType) != null || isSegment(url, mimeType)) return false
        if (UrlPatternDetector.isStaticAsset(url)) return false
        return MimeTypeDetector.isOctetStream(mimeType) ||
            (mimeType.isNullOrBlank() && UrlPatternDetector.hasMediaHint(url, acceptHeader))
    }

    fun isSegment(url: String, mimeType: String? = null): Boolean =
        UrlPatternDetector.isSegment(url) || MimeTypeDetector.isMpegTs(mimeType)
}
