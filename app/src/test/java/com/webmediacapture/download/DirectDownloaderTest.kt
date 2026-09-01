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

class DirectDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var tmp: File

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = File.createTempFile("direct", ".tmp").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    @Test fun resumesWithRangeAndRequiresRefererAndCookie() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Referer") != "https://page.test" || request.getHeader("Cookie") != "sid=1") {
                    return MockResponse().setResponseCode(403)
                }
                val range = request.getHeader("Range")
                return if (range == "bytes=4-") {
                    MockResponse().setResponseCode(206).setBody("456789").setHeader("Content-Range", "bytes 4-9/10")
                } else {
                    MockResponse().setResponseCode(200).setBody("0123456789").setHeader("Content-Length", "10")
                }
            }
        }
        val dest = File(tmp, "video.mp4")
        val partial = File(dest.absolutePath + ".part").apply { writeText("0123") }
        val context = RequestContext(mapOf("Referer" to "https://page.test", "Cookie" to "sid=1", "User-Agent" to "agent"))
        val result = DirectDownloader(OkHttpClient()).download(server.url("/video.mp4").toString(), dest, context) { _, _ -> }
        assertTrue(dest.exists())
        assertEquals("0123456789", dest.readText())
        assertEquals(10, result.downloaded)
        assertTrue(!partial.exists())
    }

    @Test fun downloadsWithParallelRangesWhenSupported() = runBlocking {
        val payload = ByteArray(32) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse()
                    .setResponseCode(200)
                    .setBody(okio.Buffer().write(payload))
                val spec = range.removePrefix("bytes=")
                val start = spec.substringBefore('-').toInt()
                val endPart = spec.substringAfter('-')
                val end = if (endPart.isBlank()) payload.size - 1 else endPart.toInt()
                val slice = payload.copyOfRange(start, end + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(okio.Buffer().write(slice))
            }
        }
        val dest = File(tmp, "parallel.mp4")
        val result = DirectDownloader(OkHttpClient(), parts = 4, minMultiBytes = 8)
            .download(server.url("/video.mp4").toString(), dest, RequestContext()) { _, _ -> }
        org.junit.Assert.assertArrayEquals(payload, dest.readBytes())
        assertEquals(32L, result.downloaded)
        assertEquals(32L, result.total)
        assertTrue(server.requestCount >= 5)
    }

    @Test fun deniedWithoutHeaders() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        val dest = File(tmp, "denied.mp4")
        try {
            DirectDownloader(OkHttpClient()).download(server.url("/hotlink.mp4").toString(), dest, RequestContext()) { _, _ -> }
            throw AssertionError("expected failure")
        } catch (error: java.io.IOException) {
            assertTrue(error.message!!.contains("403"))
        }
    }

    @Test fun fallsBackToFullGetWhenRangeProbeRejected() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.getHeader("Range") != null) {
                    MockResponse().setResponseCode(416)
                } else {
                    MockResponse().setResponseCode(200).setBody("ABCDEF").setHeader("Content-Length", "6")
                }
            }
        }
        val dest = File(tmp, "fallback.mp4")
        val result = DirectDownloader(OkHttpClient()).download(server.url("/video.mp4").toString(), dest, RequestContext()) { _, _ -> }
        assertEquals("ABCDEF", dest.readText())
        assertEquals(6, result.downloaded)
    }

    @Test fun fallsBackWhenRangeProbeReturnsOneByteWith200() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.getHeader("Range") == "bytes=0-0") {
                    MockResponse().setResponseCode(200).setBody("A").setHeader("Content-Length", "1")
                } else {
                    MockResponse().setResponseCode(200).setBody("FULLVID").setHeader("Content-Length", "7")
                }
            }
        }
        val dest = File(tmp, "onebyte.mp4")
        val result = DirectDownloader(OkHttpClient()).download(server.url("/video.mp4").toString(), dest, RequestContext()) { _, _ -> }
        assertEquals("FULLVID", dest.readText())
        assertEquals(7, result.downloaded)
    }
}
