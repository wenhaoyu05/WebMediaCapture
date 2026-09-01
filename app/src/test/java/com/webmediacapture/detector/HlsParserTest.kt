package com.webmediacapture.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {
    @Test fun parsesMasterVariantsAndResolvesRelativeUrls() {
        val result = HlsParser.parse("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e",AUDIO="stereo"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
            /video/high.m3u8
        """.trimIndent(), "https://cdn.test/root/master.m3u8")

        assertTrue(result.isMaster)
        assertEquals(2, result.variants.size)
        assertEquals("https://cdn.test/root/low/index.m3u8", result.variants[0].url)
        assertEquals(640, result.variants[0].width)
        assertEquals(360, result.variants[0].height)
        assertEquals(1800000L, result.variants[1].bitrate)
        assertEquals("https://cdn.test/video/high.m3u8", result.variants[1].url)
    }

    @Test fun parsesMediaPlaylistSegmentsAndInitMap() {
        val result = HlsParser.parseMedia(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            segment.ts
            """.trimIndent(),
            "https://cdn.test/live.m3u8",
        )
        assertEquals(7, result.mediaSequence)
        assertEquals("https://cdn.test/init.mp4", result.initSegmentUrl)
        assertEquals(1, result.segments.size)
        assertEquals("https://cdn.test/segment.ts", result.segments.single().url)
        assertEquals(7, result.segments.single().sequence)
        assertFalse(result.ended)
    }

    @Test fun sumsVodDurationAndIgnoresLivePlaylists() {
        val vod = HlsParser.parseMedia(
            """
            #EXTM3U
            #EXTINF:2.5,
            a.ts
            #EXTINF:2.5,
            b.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.test/vod.m3u8",
        )
        assertTrue(vod.ended)
        assertEquals(5.0, vod.durationSec, 0.01)
        val live = HlsParser.parseMedia(
            """
            #EXTM3U
            #EXTINF:4,
            live.ts
            """.trimIndent(),
            "https://cdn.test/live.m3u8",
        )
        assertFalse(live.ended)
    }

    @Test fun recognizesMediaPlaylistAndRejectsNonHls() {
        val result = HlsParser.parse("#EXTM3U\n#EXTINF:4,\nsegment.ts", "https://cdn.test/live.m3u8")
        assertFalse(result.isMaster)
        assertTrue(result.variants.isEmpty())
        try {
            HlsParser.parse("not a playlist", "https://cdn.test/live.m3u8")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun treatsAes128EncryptionAsNonDrm() {
        val result = HlsParser.parse("""
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.test/key.bin"
            #EXTINF:4,
            segment.ts
        """.trimIndent(), "https://cdn.test/live.m3u8")

        assertFalse(result.drmProtected)
    }

    @Test fun treatsSampleAesAndFairPlayKeyFormatsAsDrm() {
        val sampleAes = HlsParser.parse("""
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://asset/key"
            #EXTINF:4,
            segment.ts
        """.trimIndent(), "https://cdn.test/sample-aes.m3u8")
        val fairPlay = HlsParser.parse("""
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://asset/key"
            #EXTINF:4,
            segment.ts
        """.trimIndent(), "https://cdn.test/fairplay.m3u8")

        assertTrue(sampleAes.drmProtected)
        assertTrue(fairPlay.drmProtected)
    }
}
