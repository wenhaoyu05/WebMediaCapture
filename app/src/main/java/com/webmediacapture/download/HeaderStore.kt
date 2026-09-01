package com.webmediacapture.download

import com.webmediacapture.model.RequestContext

/** Persists only non-sensitive headers required to resume a local task. */
object HeaderStore {
    fun encode(context: RequestContext): String {
        val headers = context.downloadHeaders().filterKeys {
            !it.equals("Cookie", true) && !it.equals("Authorization", true)
        }
        return headers.entries.joinToString("\n") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }
    }

    fun decode(value: String): RequestContext {
        if (value.isBlank()) return RequestContext()
        val trimmed = value.trim()
        val headers = if (trimmed.startsWith("{")) decodeJsonObject(trimmed) else {
            trimmed.lineSequence().mapNotNull { line ->
                val key = line.substringBefore('=', "").let(::unescape)
                val headerValue = line.substringAfter('=', "").let(::unescape)
                if (key.isBlank()) null else key to headerValue
            }.toMap()
        }
        return RequestContext(headers)
    }

    private fun decodeJsonObject(value: String): Map<String, String> {
        val body = value.removePrefix("{").removeSuffix("}")
        if (body.isBlank()) return emptyMap()
        return Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(body)
            .associate { unescapeJson(it.groupValues[1]) to unescapeJson(it.groupValues[2]) }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=")

    private fun unescape(value: String): String = value.replace("\\=", "=").replace("\\n", "\n").replace("\\\\", "\\")

    private fun unescapeJson(value: String): String = value.replace("\\\"", "\"").replace("\\\\", "\\")
}
