package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class HlsDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var tmp: File

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = File.createTempFile("hls", ".dir").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun serve(bodies: Map<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                val body = bodies.entries.firstOrNull { path.endsWith(it.key) }?.value
                    ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(body)
            }
        }
    }

    @Test fun downloadsMasterPlaylistSegmentsAndMuxes() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            media.m3u8
        """.trimIndent()
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:1,
            a.ts
            #EXTINF:1,
            b.ts
        """.trimIndent()
        serve(mapOf("master.m3u8" to master, "media.m3u8" to media, "a.ts" to "AAAA", "b.ts" to "BBBB"))
        val output = HlsDownloader(OkHttpClient(), ConcatMuxer(), fallback = null)
            .download("task", server.url("/master.m3u8").toString(), tmp, RequestContext()) { }
        assertTrue(output.exists())
        assertEquals("AAAABBBB", output.readText())
    }

    @Test fun downloadsAllPlaylistSegmentsRegardlessOfAdLookingUrl() = runBlocking {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:1,
            ${server.url("/ad-site-i18n-sg/clip.image")}
            #EXTINF:1,
            ${server.url("/preroll/a.ts")}
            #EXTINF:1,
            ${server.url("/video/master.jpeg")}
        """.trimIndent()
        serve(
            mapOf(
                "media.m3u8" to media,
                "clip.image" to "AD",
                "a.ts" to "VID",
                "master.jpeg" to "JPG",
            ),
        )
        val output = HlsDownloader(OkHttpClient(), ConcatMuxer(), fallback = null)
            .download("task", server.url("/media.m3u8").toString(), tmp, RequestContext()) { }
        assertEquals("ADVIDJPG", output.readText())
        assertEquals(4, server.requestCount)
    }

    @Test fun retriesForbiddenManifestWithoutOriginCookie() = runBlocking {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:1,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        server.enqueue(MockResponse().setBody(media))
        server.enqueue(MockResponse().setBody("AA"))
        val ctx = RequestContext(
            mapOf(
                "Referer" to "https://playrecord.biz/embed/x",
                "Origin" to "https://pornavhd.com",
                "Cookie" to "sid=1",
            ),
        )
        val output = HlsDownloader(OkHttpClient(), ConcatMuxer(), fallback = null)
            .download("task", server.url("/master.m3u8").toString(), tmp, ctx) { }
        assertEquals("AA", output.readText())
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals(null, retry.getHeader("Origin"))
        assertEquals(null, retry.getHeader("Cookie"))
        assertEquals("https://playrecord.biz/embed/x", retry.getHeader("Referer"))
    }

    @Test fun retriesTransientSegmentError() = runBlocking {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:1,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.enqueue(MockResponse().setBody(media))
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setBody("AA"))
        val output = HlsDownloader(OkHttpClient(), ConcatMuxer(), fallback = null)
            .download("task", server.url("/media.m3u8").toString(), tmp, RequestContext()) { }
        assertEquals("AA", output.readText())
    }

    @Test fun failsWhenPlaylistHasNoSegments() = runBlocking {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-ENDLIST
        """.trimIndent()
        server.enqueue(MockResponse().setBody(media))
        try {
            HlsDownloader(OkHttpClient(), ConcatMuxer(), fallback = null)
                .download("task", server.url("/empty.m3u8").toString(), tmp, RequestContext()) { }
            throw AssertionError("expected IOException")
        } catch (error: java.io.IOException) {
            assertTrue(error.message!!.contains("no media segments"))
        }
    }
}
