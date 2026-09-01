package com.webmediacapture.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {
    @Test fun formatsBytesWithTwoDecimals() {
        assertEquals("500 B", ByteFormat.format(500))
        assertEquals("1.50 KB", ByteFormat.format(1536))
        assertEquals("1.00 MB", ByteFormat.format(1024L * 1024))
        assertEquals("1.50 GB", ByteFormat.format((1024L * 1024 * 1024 * 3) / 2))
    }
}
