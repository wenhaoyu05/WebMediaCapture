package com.webmediacapture.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSniffTest {
    @Test fun acceptsJpegPngAndStatedImageType() {
        assertTrue(ImageSniff.isImage("image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(12)))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(12)
        assertTrue(ImageSniff.isImage(null, png))
        assertTrue(ImageSniff.isImage("image/webp", ByteArray(16) { 1 }))
        assertFalse(ImageSniff.isImage("text/html", "<html>".toByteArray() + ByteArray(12)))
        assertFalse(ImageSniff.isImage("image/jpeg", byteArrayOf(1, 2, 3)))
    }
}
