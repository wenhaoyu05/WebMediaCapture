package com.webmediacapture.download

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class JsPackrDecoderTest {

    private val LINKS = Pattern.compile("\"hls([23])\":\"([^\"]+)\"")

    private fun resource(name: String): String =
        File(javaClass.getResource(name)!!.toURI()).readText()

    @Test
    fun decodesRealPlayrecordEmbedAndFindsAlternateHlsSources() {
        val html = resource("/embed2.html")
        val decoded = JsPackrDecoder.decode(html) ?: throw AssertionError("packr should decode")

        val urls = mutableListOf<String>()
        val m = LINKS.matcher(decoded)
        while (m.find()) m.group(2)?.let { urls.add(it) }

        val hls3 = urls.firstOrNull { it.contains("hls3") }
        val hls2 = urls.firstOrNull { it.contains("hls2") }
        assertNotNull("hls3 backup source recovered", hls3)
        assertNotNull("hls2 backup source recovered", hls2)
        assertTrue("hls3 is absolute", hls3!!.startsWith("http"))
        assertTrue("hls2 is absolute", hls2!!.startsWith("http"))
        assertTrue("hls3 keeps signed query or path intact", hls3.contains(".urlset") || hls3.contains(".txt"))
    }
}
