package com.webmediacapture.detector

import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.MediaVariant
import com.webmediacapture.model.RequestContext
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateDeduplicatorTest {
    private fun candidate(url: String, confidence: Int, height: Int? = null, variants: List<MediaVariant> = emptyList(), context: RequestContext = RequestContext()) =
        MediaCandidate(pageSessionId = "session", pageUrl = "https://page.test", mediaUrl = url, type = MediaType.DIRECT,
            confidence = confidence, height = height, variants = variants, requestContext = context, source = DetectionSource.DOM)

    @Test fun mergesTrackingVariantsAndPrefersHigherConfidence() {
        val old = candidate("https://cdn.test/v.mp4?utm_source=ad", 40, 360, listOf(MediaVariant("https://cdn.test/low")))
        val incoming = candidate("https://CDN.test/v.mp4?gclid=x", 80, 720, listOf(MediaVariant("https://cdn.test/high")),
            RequestContext(mapOf("Cookie" to "sid=1")))
        val result = CandidateDeduplicator().merge(listOf(old), incoming)

        assertEquals(1, result.size)
        assertEquals(80, result.single().confidence)
        assertEquals(720, result.single().height)
        assertEquals(2, result.single().variants.size)
        assertEquals("sid=1", result.single().requestContext.value("cookie"))
    }

    @Test fun mergeKeepsPageTitleInsteadOfCdnFileName() {
        val old = candidate("https://cdn.test/v.mp4", 40, 360).copy(title = "master.m3u8")
        val incoming = candidate("https://cdn.test/v.mp4", 80, 720).copy(title = "网页视频名")
        val result = CandidateDeduplicator().merge(listOf(old), incoming)
        assertEquals("网页视频名", result.single().title)
    }

    @Test fun mergeKeepsThumbnailUrl() {
        val old = candidate("https://cdn.test/v.mp4", 40, 360).copy(thumbnailUrl = "https://cdn.test/a.jpg")
        val incoming = candidate("https://cdn.test/v.mp4", 80, 720)
        val result = CandidateDeduplicator().merge(listOf(old), incoming)
        assertEquals("https://cdn.test/a.jpg", result.single().thumbnailUrl)
    }

    @Test fun dropsSegmentsAndOrdersDistinctCandidates() {
        val dedup = CandidateDeduplicator()
        val segment = candidate("https://cdn.test/seg.ts", 99)
        val low = candidate("https://cdn.test/low.mp4", 90, 360)
        val high = candidate("https://cdn.test/high.mp4", 50, 1080)
        val result = dedup.merge(dedup.merge(emptyList(), low), high)
        assertEquals(listOf("https://cdn.test/high.mp4", "https://cdn.test/low.mp4"), result.map { it.mediaUrl })
        assertEquals(2, dedup.merge(result, segment).size)
    }

    @Test fun keepPrimaryKeepsAllVideosSortedByDuration() {
        val dedup = CandidateDeduplicator()
        val main = candidate("https://cdn.test/main.mp4", 70, 720)
            .copy(role = com.webmediacapture.model.MediaRole.MAIN, durationSec = 30.0)
        val float = candidate("https://cdn.test/float.mp4", 90, 240)
            .copy(role = com.webmediacapture.model.MediaRole.UNKNOWN, durationSec = 120.0)
        val merged = dedup.merge(dedup.merge(emptyList(), main), float)
        assertEquals(
            listOf("https://cdn.test/float.mp4", "https://cdn.test/main.mp4"),
            dedup.keepPrimary(merged).map { it.mediaUrl },
        )
    }

    @Test fun keepPrimaryPrefers1080HlsOverZeroHeightPreview() {
        val dedup = CandidateDeduplicator()
        val preview = candidate(
            "https://z6v2p9a8.bkcdn.net/library/914186/87e32a468b2ca40b46f23856c1349c8efc4569bc.mp4",
            75,
            0,
        )
        val hls = MediaCandidate(
            pageSessionId = "session",
            pageUrl = "https://page.test",
            mediaUrl = "https://playrecord.biz/stream/i8w5TY4DHY7QevlyNbCjfA/hjkrhuihghfvu/1788195906/42842889/master.m3u8",
            type = MediaType.HLS,
            source = DetectionSource.WEBVIEW_NETWORK,
            height = 1080,
            width = 1920,
            confidence = 95,
            variants = listOf(MediaVariant("https://playrecord.biz/stream/i8w5TY4DHY7QevlyNbCjfA/hjkrhuihghfvu/1788195906/42842889/1080.m3u8", height = 1080)),
        )
        val merged = dedup.merge(dedup.merge(emptyList(), preview), hls)
        val kept = dedup.keepPrimary(merged)
        assertEquals(listOf(hls.mediaUrl, preview.mediaUrl), merged.map { it.mediaUrl })
        assertEquals(listOf(hls.mediaUrl), kept.map { it.mediaUrl })
    }

    @Test fun keepPrimaryKeepsUnknownDurationClipsAndHidesLiveThumbs() {
        val dedup = CandidateDeduplicator()
        val preview = candidate("https://cdn.test/related.mp4", 75, 0)
        val liveThumb = MediaCandidate(
            pageSessionId = "session",
            pageUrl = "https://page.test",
            mediaUrl = "https://media-hls.growcdnssedge.com/b-hls-02/1/1_240p.m3u8",
            type = MediaType.HLS,
            source = DetectionSource.WEBVIEW_NETWORK,
            height = 240,
            confidence = 95,
        )
        val merged = dedup.merge(dedup.merge(emptyList(), preview), liveThumb)
        assertEquals(listOf("https://cdn.test/related.mp4"), dedup.keepPrimary(merged).map { it.mediaUrl })
    }

    @Test fun collapsesHlsMasterVariantAndSegmentsIntoOneCandidate() {
        val dedup = CandidateDeduplicator()
        val master = MediaCandidate(
            pageSessionId = "session",
            pageUrl = "https://page.test",
            mediaUrl = "https://cdn.test/root/master.m3u8",
            type = MediaType.HLS,
            source = DetectionSource.WEBVIEW_NETWORK,
            variants = listOf(MediaVariant("https://cdn.test/root/720.m3u8", height = 720)),
            confidence = 95,
        )
        val variant = MediaCandidate(
            pageSessionId = "session",
            pageUrl = "https://page.test",
            mediaUrl = "https://cdn.test/root/720.m3u8",
            type = MediaType.HLS,
            source = DetectionSource.WEBVIEW_NETWORK,
            confidence = 75,
        )
        val afterMaster = dedup.merge(emptyList(), master)
        val afterVariant = dedup.merge(afterMaster, variant)
        val afterSeg1 = dedup.merge(afterVariant, candidate("https://cdn.test/root/segment001.ts", 99))
        val afterSeg2 = dedup.merge(afterSeg1, candidate("https://cdn.test/root/segment002.ts", 99))
        assertEquals(1, afterSeg2.size)
        assertEquals("https://cdn.test/root/master.m3u8", afterSeg2.single().mediaUrl)
    }
}
