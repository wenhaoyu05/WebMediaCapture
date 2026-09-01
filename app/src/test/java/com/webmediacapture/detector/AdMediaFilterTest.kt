package com.webmediacapture.detector

import com.webmediacapture.model.MediaRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMediaFilterTest {
    @Test
    fun flagsAdNetworksAndKeepsContentUrls() {
        assertTrue(AdMediaFilter.isAdUrl("https://pagead2.googlesyndication.com/pagead/js/ads.js"))
        assertTrue(AdMediaFilter.isAdUrl("https://static.doubleclick.net/instream/ad_status.js"))
        assertTrue(AdMediaFilter.isAdUrl("https://cdn.example.com/preroll/clip.mp4"))
        assertTrue(AdMediaFilter.isAdUrl("https://cdn.example.com/vast/vmap.xml"))
        assertFalse(AdMediaFilter.isAdUrl("https://cdn.example.com/video/master.m3u8"))
        assertFalse(AdMediaFilter.isAdUrl("http://127.0.0.1:17845/demo/clip.mp4"))
        assertFalse(AdMediaFilter.isAdUrl("https://cdn.example.com/adapter/stream.mp4"))
        assertTrue(AdMediaFilter.isAdUrl("https://p16-ad-site-sign-sg.tiktokcdn.com/ad-site-i18n-sg/clip.image"))
        val playrecord = "https://playrecord.biz/stream/i8w5TY4DHY7QevlyNbCjfA/hjkrhuihghfvu/1788195906/42842889/master.m3u8"
        assertFalse(AdMediaFilter.isAdUrl(playrecord))
        assertFalse(AdMediaFilter.isAdUrl("https://z6v2p9a8.bkcdn.net/library/914186/87e32a468b2ca40b46f23856c1349c8efc4569bc.mp4"))
        assertEquals(MediaRole.OVERLAY, AdMediaFilter.parseRole("overlay"))
        assertEquals(MediaRole.MAIN, AdMediaFilter.parseRole("MAIN"))
        assertEquals(MediaRole.UNKNOWN, AdMediaFilter.parseRole(null))
    }

    @Test
    fun flagsAdTrackerRefererButKeepsContentReferer() {
        assertTrue(AdMediaFilter.isAdReferer("https://t.nettrck.store/"))
        assertTrue(AdMediaFilter.isAdReferer("https://adclick.example.net/r/xyz"))
        assertTrue(AdMediaFilter.isAdReferer("https://www.doubleclick.net/pagead"))
        assertFalse(AdMediaFilter.isAdReferer("https://playrecord.biz/embed/24a0iszo36jz"))
        assertFalse(AdMediaFilter.isAdReferer("https://pornavhd.com/2026/08/29/kuzu_v0_101_20260825/"))
        assertFalse(AdMediaFilter.isAdReferer(null))
        assertFalse(AdMediaFilter.isAdReferer(""))
    }
}
