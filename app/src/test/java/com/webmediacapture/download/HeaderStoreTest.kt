package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeaderStoreTest {
    @Test fun persistsNonSensitiveHeadersOnly() {
        val encoded = HeaderStore.encode(
            RequestContext(mapOf("Cookie" to "sid=1", "Authorization" to "Bearer x", "Referer" to "https://page.test", "User-Agent" to "agent")),
        )
        assertFalse(encoded.contains("sid=1"))
        assertFalse(encoded.contains("Bearer"))
        val decoded = HeaderStore.decode(encoded)
        assertEquals("https://page.test", decoded.value("Referer"))
        assertEquals("agent", decoded.value("User-Agent"))
        assertEquals(null, decoded.value("Cookie"))
    }
}
