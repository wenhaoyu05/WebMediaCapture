package com.webmediacapture.network

import com.webmediacapture.model.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkProbeTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun fallsBackFromHeadToRangedGet() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setBody("0123456789"))
        val result = NetworkProbe(OkHttpClient()).inspect(server.url("/video").toString(), RequestContext())
        val head = server.takeRequest()
        val get = server.takeRequest()
        assertEquals("HEAD", head.method)
        assertEquals("GET", get.method)
        assertEquals("bytes=0-4095", get.getHeader("Range"))
        assertEquals("video/mp4", result.mimeType)
        assertArrayEquals("0123456789".toByteArray(), result.bodyPrefix)
    }

    @Test fun propagatesRefererAndCookieAndRetries403WithHeaders() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Referer") == "https://page.test" && request.getHeader("Cookie") == "sid=1")
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4")
                else MockResponse().setResponseCode(403)
        }
        val url = server.url("/protected").toString()
        NetworkProbe(OkHttpClient()).inspect(url, RequestContext())
        val result = NetworkProbe(OkHttpClient()).inspect(url, RequestContext(mapOf("Referer" to "https://page.test", "Cookie" to "sid=1")))
        val deniedHead = server.takeRequest()
        val deniedGet = server.takeRequest()
        val accepted = server.takeRequest()
        assertEquals(3, server.requestCount)
        assertEquals("HEAD", deniedHead.method)
        assertEquals("GET", deniedGet.method)
        assertEquals("https://page.test", accepted.getHeader("Referer"))
        assertEquals("sid=1", accepted.getHeader("Cookie"))
        assertEquals("video/mp4", result.mimeType)
    }

    @Test fun followsRedirectAndReportsFinalUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4"))
        val result = NetworkProbe(OkHttpClient()).inspect(server.url("/start").toString(), RequestContext())
        assertTrue(result.finalUrl.endsWith("/final"))
        assertEquals("/start", server.takeRequest().path)
        assertEquals("/final", server.takeRequest().path)
    }
}
