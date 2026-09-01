package com.webmediacapture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestContextTest {
    @Test fun headerLookupAndDownloadHeadersAreCaseInsensitiveAndAllowlisted() {
        val context = RequestContext(mapOf("cookie" to "sid=1", "REFERER" to "https://page.test", "X-Trace" to "drop", "User-Agent" to "agent"))
        assertEquals("sid=1", context.value("Cookie"))
        assertNull(context.value("Authorization"))
        assertEquals(setOf("cookie", "REFERER", "User-Agent"), context.downloadHeaders().keys)
    }
}
