package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DashDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var tmp: File

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = File.createTempFile("dash", ".dir").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    @Test fun downloadsVideoAndAudioRepresentations() = runBlocking {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" width="640" height="360" bandwidth="500000">
                    <SegmentList>
                      <Initialization sourceURL="v-init.mp4"/>
                      <SegmentURL media="v1.m4s"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Representation id="a" bandwidth="64000">
                    <SegmentList>
                      <SegmentURL media="a1.m4s"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                val body = when {
                    path.endsWith("manifest.mpd") -> mpd
                    path.endsWith("v-init.mp4") -> "VINIT"
                    path.endsWith("v1.m4s") -> "VSEG"
                    path.endsWith("a1.m4s") -> "ASEG"
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        val output = DashDownloader(OkHttpClient(), ConcatMuxer(), fallback = null)
            .download("dash", server.url("/manifest.mpd").toString(), tmp, RequestContext()) { }
        assertTrue(output.exists())
        assertTrue(output.length() > 0)
    }
}
