package com.webmediacapture.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashParserTest {
    @Test fun parsesRepresentationsSegmentListAndDrm() {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT4S">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v720" width="1280" height="720" bandwidth="1800000" codecs="avc1.4d401f">
                    <SegmentList>
                      <Initialization sourceURL="init.mp4"/>
                      <SegmentURL media="seg1.m4s"/>
                      <SegmentURL media="seg2.m4s"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Representation id="a64" bandwidth="64000" codecs="mp4a.40.2">
                    <SegmentList>
                      <SegmentURL media="audio.m4s"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val parsed = DashParser.parse(mpd, "https://cdn.test/manifest.mpd")
        assertFalse(parsed.drmProtected)
        assertEquals(2, parsed.representations.size)
        val video = parsed.representations.first { it.isVideo }
        assertEquals(1280, video.width)
        assertEquals(720, video.height)
        assertEquals("https://cdn.test/init.mp4", video.initUrl)
        assertEquals(listOf("https://cdn.test/seg1.m4s", "https://cdn.test/seg2.m4s"), video.mediaUrls)
        assertEquals(1, parsed.variants().size)
        assertEquals("v720", parsed.variants().single().formatId)
    }

    @Test fun marksContentProtectionAsDrm() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet>
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                  <Representation id="v" mimeType="video/mp4" width="1920" height="1080"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        assertTrue(DashParser.parse(mpd).drmProtected)
    }
}
