package com.webmediacapture.download

import java.util.regex.Pattern

/**
 * Minimal decoder for Dean Edward's "packed" JavaScript obfuscation scheme as
 * emitted by VidHide/playrecord embeds. It reproduces only the string
 * de-obfuscation `while` loop, which is enough to recover the player's real
 * `links = {...}` object (hls4/hls3/hls2 source URLs).
 */
object JsPackrDecoder {

    // tail: ,<base>,<count>,'k0|k1|...'.split(...
    private val TAIL: Pattern = Pattern.compile(",(\\d+),(\\d+),'([^']*)'\\.split")

    /** Returns the decoded body, or null if the input has no decodable packr call. */
    fun decode(html: String): String? {
        val i = html.indexOf("function(p,a,c,k,e,d)")
        if (i < 0) return null
        val end = (i + 20000).coerceAtMost(html.length)
        val seg = html.substring(i, end)

        val tail = TAIL.matcher(seg)
        if (!tail.find()) return null
        val base = tail.group(1)?.toIntOrNull() ?: return null
        val count = tail.group(2)?.toIntOrNull() ?: return null
        val kList = tail.group(3)?.split("|").orEmpty()
        val tailMarker = ",$base,$count,'"

        // payload opens right after `return p}(`
        val openIdx = seg.indexOf("return p}(")
        if (openIdx < 0) return null
        val payloadStart = seg.indexOf("'", openIdx) + 1
        if (payloadStart <= 0) return null
        val payloadEnd = seg.indexOf(tailMarker, payloadStart)
        if (payloadEnd < 0) return null
        val payload = seg.substring(payloadStart, payloadEnd)

        var out = payload
        for (c in count - 1 downTo 0) {
            val repl = kList.getOrNull(c).orEmpty()
            val token = toBase(c, base)
            out = out.replace(Regex("\\b" + Pattern.quote(token) + "\\b"), repl)
        }
        return out
    }

    private fun toBase(n: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (n == 0) return "0"
        var value = n
        val sb = StringBuilder()
        while (value > 0) {
            sb.insert(0, digits[value % base])
            value /= base
        }
        return sb.toString()
    }
}
