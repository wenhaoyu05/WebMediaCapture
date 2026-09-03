package com.webmediacapture.util

object ImageSniff {
    fun isImage(contentType: String?, bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.startsWith("image/") && type != "image/svg+xml") return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()) return true
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return true
        return bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
    }
}
