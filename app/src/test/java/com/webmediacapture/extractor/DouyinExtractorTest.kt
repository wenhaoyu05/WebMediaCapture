package com.webmediacapture.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinExtractorTest {
    @Test fun stripsWatermarkAndForcesHttps() {
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=x",
            DouyinExtractor.nowatermark("http://aweme.snssdk.com/aweme/v1/playwm/?video_id=x"),
        )
        assertEquals(
            "https://cdn.test/play/a",
            DouyinExtractor.nowatermark("https://cdn.test/playwm/a"),
        )
    }

    @Test fun readsPlayUrlFromPagePayload() {
        val html = """<html><a href="https://aweme.snssdk.com/aweme/v1/playwm/?video_id=x">x</a></html>"""
        val info = DouyinExtractor.fromPagePayload(html)
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=x", info?.playUrl)
        assertEquals("{\"a\":1}", DouyinExtractor.parseJsResult("\"{\\\"a\\\":1}\""))
        assertEquals("", DouyinExtractor.parseJsResult("null"))
    }
}
