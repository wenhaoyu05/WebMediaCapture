package com.webmediacapture.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinLinksTest {
    @Test fun detectsShareAndVideoUrls() {
        assertTrue(DouyinLinks.isDouyinVideo("https://v.douyin.com/iAbC123/"))
        assertTrue(DouyinLinks.isDouyinVideo("https://www.douyin.com/video/7485123456789012345"))
        assertTrue(DouyinLinks.isDouyinVideo("https://www.iesdouyin.com/share/video/7485123456789012345"))
        assertTrue(DouyinLinks.isDouyinVideo("https://www.douyin.com/discover?modal_id=7485123456789012345"))
        assertFalse(DouyinLinks.isDouyinVideo("https://www.douyin.com/"))
        assertFalse(DouyinLinks.isDouyinVideo("https://example.com/video/1234567"))
    }

    @Test fun readsVideoId() {
        assertEquals("7485123456789012345", DouyinLinks.videoId("https://www.douyin.com/video/7485123456789012345?from=web"))
        assertEquals("7485123456789012345", DouyinLinks.videoId("https://www.iesdouyin.com/share/video/7485123456789012345/"))
        assertEquals("7485123456789012345", DouyinLinks.videoId("https://www.douyin.com/jingxuan?modal_id=7485123456789012345"))
        assertNull(DouyinLinks.videoId("https://v.douyin.com/iAbC123/"))
    }

    @Test fun canonicalizesWatchUrlForExtractors() {
        assertEquals(
            "https://www.douyin.com/video/7485123456789012345",
            DouyinLinks.pageUrlForExtract("https://www.iesdouyin.com/share/video/7485123456789012345/?region=CN"),
        )
        assertEquals("https://v.douyin.com/iAbC123/", DouyinLinks.pageUrlForExtract("https://v.douyin.com/iAbC123/"))
        assertTrue(DouyinLinks.isMediaUrl("https://v3-web.douyinvod.com/tos/cn/obj/x"))
        assertTrue(DouyinLinks.isMediaUrl("https://aweme.snssdk.com/aweme/v1/play/?video_id=x"))
        assertFalse(DouyinLinks.isMediaUrl("https://www.douyin.com/video/7485123456789012345"))
    }
}
