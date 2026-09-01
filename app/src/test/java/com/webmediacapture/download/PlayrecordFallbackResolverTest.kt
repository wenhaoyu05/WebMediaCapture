package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayrecordFallbackResolverTest {
    @Test fun cdnContextKeepsEmbedRefererAndDropsOriginCookie() {
        val out = PlayrecordFallbackResolver.cdnContext(
            RequestContext(
                mapOf(
                    "Referer" to "https://playrecord.biz/embed/24a0iszo36jz",
                    "Origin" to "https://pornavhd.com",
                    "Cookie" to "sid=1",
                    "User-Agent" to "agent",
                ),
            ),
        )
        assertEquals("https://playrecord.biz/embed/24a0iszo36jz", out.value("Referer"))
        assertEquals(null, out.value("Origin"))
        assertEquals(null, out.value("Cookie"))
        assertEquals("agent", out.value("User-Agent"))
    }

    @Test fun cdnContextDoesNotUseStreamManifestAsReferer() {
        val out = PlayrecordFallbackResolver.cdnContext(
            RequestContext(
                mapOf("Referer" to "https://playrecord.biz/stream/x/hjkrhuihghfvu/1/42842889/index-f3-v1-a1.m3u8"),
            ),
        )
        assertEquals("https://playrecord.biz/", out.value("Referer"))
        assertEquals(null, out.value("Origin"))
    }
}
