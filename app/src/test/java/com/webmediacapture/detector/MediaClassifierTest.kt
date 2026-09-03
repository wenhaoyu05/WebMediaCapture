package com.webmediacapture.detector

import com.webmediacapture.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {
    @Test fun classifiesUrlsAndMimeTypes() {
        assertEquals(MediaType.DIRECT, MediaClassifier.fromUrl("https://cdn.test/video.MP4?token=1"))
        assertEquals(MediaType.DIRECT, MediaClassifier.fromUrl("https://cdn.test/video.mp4?token=123"))
        assertEquals(MediaType.HLS, MediaClassifier.fromUrl("https://cdn.test/manifest.m3u8"))
        assertEquals(MediaType.HLS, MediaClassifier.fromUrl("https://cdn.test/master.m3u8?auth=xxx"))
        assertEquals(MediaType.HLS, MediaClassifier.fromUrl("https://cdn.test/play?id=123&type=m3u8"))
        assertEquals(MediaType.HLS, MediaClassifier.fromUrl("https://playrecord.biz/dl?op=get&file_code=x&hls4"))
        assertEquals(
            MediaType.HLS,
            MediaClassifier.fromUrl("https://cdn.test/hls3/01/x/urlset/master.txt"),
        )
        assertNull(MediaClassifier.fromUrl("https://cdn.test/readme.txt"))
        assertEquals(
            MediaType.HLS,
            MediaClassifier.fromUrl(
                "https://playrecord.biz/dl?op=get&file_code=x&hash=y&embed=1&referer=https://pornavhd.com/2026/08/29/foo/&adb=0&hls4",
            ),
        )
        assertTrue(UrlPatternDetector.isHlsGateway("https://playrecord.biz/dl?op=get&file_code=x&hls4"))
        assertTrue(PreviewMediaFilter.isPreviewUrl("https://media-hls.growcdnssedge.com/b-hls-12/1/1_240p.m3u8"))
        assertEquals(
            "https://cdn.test/hls2/01/urlset/master.m3u8",
            HlsPlaylistLocator.fromSegmentUrl("https://cdn.test/hls2/01/urlset/seg-1-f1-v1-a1.ts"),
        )
        assertEquals(
            listOf("https://acek-cdn.test/hls2/x/master.m3u8"),
            HlsPlaylistLocator.embeddedManifestUrls("""{"file":"//acek-cdn.test/hls2/x/master.m3u8"}"""),
        )
        assertEquals(
            listOf("https://acek-cdn.test/master.m3u8"),
            HlsPlaylistLocator.embeddedManifestUrls("""{"src":"https:\/\/acek-cdn.test\/master.m3u8"}"""),
        )
        assertEquals(MediaType.DASH, MediaClassifier.fromUrl("https://cdn.test/manifest.mpd"))
        assertEquals(MediaType.DASH, MediaClassifier.fromUrl("https://cdn.test/play?format=mpd"))
        assertEquals(MediaType.DIRECT, MediaClassifier.fromUrl("https://cdn.test/stream?format=mp4"))
        assertEquals(MediaType.DIRECT, MediaClassifier.fromUrl("https://v3-web.douyinvod.com/tos/cn/obj/x?a=1"))
        assertEquals(MediaType.DIRECT, MediaClassifier.fromUrl("https://aweme.snssdk.com/aweme/v1/play/?video_id=x"))
        assertEquals(MediaType.DIRECT, MediaClassifier.fromMime("video/mp4; codecs=avc1"))
        assertEquals(MediaType.DIRECT, MediaClassifier.fromMime("video/webm"))
        assertEquals(MediaType.AUDIO, MediaClassifier.fromMime("audio/aac"))
        assertEquals(MediaType.HLS, MediaClassifier.fromMime("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(MediaType.DASH, MediaClassifier.fromMime("application/dash+xml"))
        assertNull(MediaClassifier.fromMime("application/octet-stream"))
        assertNull(MediaClassifier.fromUrl("https://cdn.test/playlist"))
        assertNull(MediaClassifier.fromUrl("https://go.stripchatgirls.com/abc.gif?format=hls"))
        assertNull(MediaClassifier.fromUrl("https://cdn.test/pixel.gif?type=m3u8"))
    }

    @Test fun excludesBlobAndSegmentsAndIdentifiesProbeNeeds() {
        assertEquals(null, MediaClassifier.fromUrl("blob:https://page.test/id"))
        assertTrue(MediaClassifier.isSegment("https://cdn.test/part.ts"))
        assertTrue(MediaClassifier.isSegment("https://cdn.test/part", "video/mp2t"))
        assertFalse(MediaClassifier.needsProbe("https://cdn.test/unknown", null))
        assertTrue(MediaClassifier.needsProbe("https://cdn.test/videoplayback", null))
        assertTrue(MediaClassifier.needsProbe("https://cdn.test/asset", "application/octet-stream", "video/mp4"))
        assertFalse(MediaClassifier.needsProbe("https://cdn.test/video.mp4", "application/octet-stream"))
        assertFalse(MediaClassifier.needsProbe("https://cdn.test/app.js", null))
    }
}
