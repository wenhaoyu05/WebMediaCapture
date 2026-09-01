package com.webmediacapture.detector

import com.webmediacapture.model.MediaType
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.NetworkProbe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaDetectorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun marksDrmHlsCandidatesAsDrmProtectedButLeavesAes128AsHls() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4,
            segment.ts
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://asset/key"
            #EXTINF:4,
            segment.ts
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://asset/key"
            #EXTINF:4,
            segment.ts
        """.trimIndent()))

        val detector = MediaDetector(NetworkProbe(OkHttpClient()))
        val aes128 = detector.detect(request("/aes128.m3u8"))
        val sampleAes = detector.detect(request("/sample-aes.m3u8"))
        val fairPlay = detector.detect(request("/fairplay.m3u8"))

        assertEquals(MediaType.HLS, aes128?.type)
        assertEquals(MediaType.DRM_PROTECTED, sampleAes?.type)
        assertEquals(MediaType.DRM_PROTECTED, fairPlay?.type)
    }

    @Test fun detectsMp4AndMimeOnlyHlsAndIgnoresBlob() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("#EXTM3U\n#EXTINF:1,\nseg.ts"))
        val detector = MediaDetector(NetworkProbe(OkHttpClient()))
        val mp4 = detector.detect(request("/clip.mp4", mime = "video/mp4").copy(title = "网页片名"))
        val hls = detector.detect(ObservedRequest(
            url = server.url("/playlist").toString(),
            method = "GET",
            mimeType = "application/vnd.apple.mpegurl",
            requestContext = RequestContext(),
            pageUrl = "https://page.test/video",
            pageSessionId = "session",
        ))
        val blob = detector.detect(
            ObservedRequest(
                url = "blob:https://page.test/id",
                method = "GET",
                requestContext = RequestContext(),
                pageUrl = "https://page.test/video",
                pageSessionId = "session",
            ),
        )
        assertEquals(MediaType.DIRECT, mp4?.type)
        assertEquals("网页片名", mp4?.title)
        assertEquals(MediaType.HLS, hls?.type)
        assertEquals(null, blob)
        val ad = detector.detect(request("/preroll/clip.mp4", mime = "video/mp4"))
        assertEquals(null, ad)
    }

    @Test fun stillDetectsHlsPlaylistsWhoseSegmentsAreAds() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1920x1080
            decoy.m3u8
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXTINF:10,
            https://p16-ad-site-sign-sg.tiktokcdn.com/ad-site-i18n-sg/clip.image
            #EXT-X-ENDLIST
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720
            real.m3u8
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXTINF:10,
            seg.ts
            #EXT-X-ENDLIST
        """.trimIndent()))
        val detector = MediaDetector(NetworkProbe(OkHttpClient()))
        val decoy = detector.detect(request("/decoy-master.m3u8"))
        assertEquals(MediaType.HLS, decoy?.type)
        assertEquals(1080, decoy?.height)
        val kept = detector.detect(request("/real-master.m3u8"))
        assertEquals(MediaType.HLS, kept?.type)
        assertEquals(720, kept?.height)
    }

    @Test fun promotesTsSegmentsToSiblingMasterPlaylist() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1920x1080
            index.m3u8
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXTINF:10,
            seg-1-f1-v1-a1.ts
            #EXT-X-ENDLIST
        """.trimIndent()))
        val detector = MediaDetector(NetworkProbe(OkHttpClient()))
        val found = detector.detect(request("/urlset/seg-1-f1-v1-a1.ts", mime = "video/mp2t"))
        assertEquals(MediaType.HLS, found?.type)
        assertEquals(1080, found?.height)
        assertTrue(found?.mediaUrl?.endsWith("/urlset/master.m3u8") == true)
        assertEquals(null, detector.detect(request("/urlset/seg-2-f1-v1-a1.ts", mime = "video/mp2t")))
    }

    @Test fun followsEmbeddedM3u8InsideDlResponse() = runBlocking {
        val realMaster = server.url("/acek/master.m3u8").toString()
        server.enqueue(MockResponse().setBody("""{"file":"$realMaster"}"""))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1920x1080
            index.m3u8
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            #EXTM3U
            #EXTINF:10,
            seg.ts
            #EXT-X-ENDLIST
        """.trimIndent()))
        val detector = MediaDetector(NetworkProbe(OkHttpClient()))
        val found = detector.detect(request("/dl?op=get&hls4", mime = "text/plain"))
        assertEquals(MediaType.HLS, found?.type)
        assertEquals(1080, found?.height)
        assertTrue(found?.mediaUrl?.contains("/acek/master.m3u8") == true)
    }

    private fun request(path: String, mime: String? = "application/vnd.apple.mpegurl") = ObservedRequest(
        url = server.url(path).toString(),
        method = "GET",
        mimeType = mime,
        requestContext = RequestContext(),
        pageUrl = "https://page.test/video",
        pageSessionId = "session",
    )
}
