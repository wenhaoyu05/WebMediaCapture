package com.webmediacapture.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {
    @Test fun redactsSecrets() {
        val redacted = SafeLog.redact("Cookie: sid=secret; Authorization: Bearer abc token=xyz signature=sig")
        assertTrue(redacted.contains("[REDACTED]"))
        assertFalse(redacted.contains("sid=secret"))
        assertFalse(redacted.contains("Bearer abc"))
        assertFalse(redacted.contains("token=xyz"))
        assertFalse(redacted.contains("signature=sig"))
    }
}
