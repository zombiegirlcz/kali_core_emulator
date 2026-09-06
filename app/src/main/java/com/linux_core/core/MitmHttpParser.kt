package com.linux_core.core

import java.io.ByteArrayOutputStream

data class ParsedHttpMessage(
    val isRequest: Boolean,
    val method: String?,
    val path: String?,
    val status: Int?,
    val httpVersion: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val bodyTruncated: Boolean
)

/**
 * Incrementally parses HTTP/1.x messages from a decrypted TLS byte stream.
 */
class Http1StreamParser(
    private val maxBodyBytes: Int = 65_536,
    private val onMessage: (ParsedHttpMessage) -> Unit
) {
    private val buffer = ByteArrayOutputStream()

    fun feed(data: ByteArray) {
        if (data.isEmpty()) return
        buffer.write(data)
        drain()
    }

    fun reset() {
        buffer.reset()
    }

    private fun drain() {
        while (true) {
            val raw = buffer.toByteArray()
            val consumed = tryParseOne(raw) ?: break
            if (consumed <= 0) break
            val remaining = raw.size - consumed
            buffer.reset()
            if (remaining > 0) {
                buffer.write(raw, consumed, remaining)
            }
        }
    }

    private fun tryParseOne(raw: ByteArray): Int? {
        val headerEnd = indexOf(raw, "\r\n\r\n".toByteArray()) ?: return null
        val headerBytes = raw.copyOfRange(0, headerEnd)
        val headerText = headerBytes.toString(Charsets.ISO_8859_1)
        val headerLines = headerText.split("\r\n")
        if (headerLines.isEmpty()) return null

        val firstLine = headerLines[0]
        val isRequest: Boolean
        val method: String?
        val path: String?
        val status: Int?
        val httpVersion: String

        if (firstLine.startsWith("HTTP/")) {
            isRequest = false
            method = null
            path = null
            val parts = firstLine.split(' ', limit = 3)
            if (parts.size < 2) return null
            httpVersion = parts[0].removePrefix("HTTP/")
            status = parts[1].toIntOrNull() ?: return null
        } else {
            isRequest = true
            status = null
            val parts = firstLine.split(' ', limit = 3)
            if (parts.size < 3) return null
            method = parts[0]
            path = parts[1]
            httpVersion = parts[2].removePrefix("HTTP/")
        }

        val headers = mutableMapOf<String, String>()
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = if (headers.containsKey(name)) "${headers[name]}, $value" else value
        }

        val bodyStart = headerEnd + 4
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        val contentLength = headers["content-length"]?.toLongOrNull()

        return when {
            transferEncoding?.contains("chunked") == true -> {
                parseChunkedBody(raw, bodyStart, isRequest, method, path, status, httpVersion, headers)
            }
            contentLength != null -> {
                val bodyLen = contentLength.toInt()
                val totalNeeded = bodyStart + bodyLen
                if (raw.size < totalNeeded) return null
                val (body, truncated) = extractBody(raw, bodyStart, bodyLen)
                onMessage(
                    ParsedHttpMessage(
                        isRequest, method, path, status, httpVersion, headers, body, truncated
                    )
                )
                totalNeeded
            }
            isRequest -> {
                // GET/HEAD etc. — no body
                onMessage(
                    ParsedHttpMessage(
                        isRequest, method, path, status, httpVersion, headers, ByteArray(0), false
                    )
                )
                bodyStart
            }
            else -> {
                // Response without Content-Length — store headers only
                onMessage(
                    ParsedHttpMessage(
                        isRequest, method, path, status, httpVersion, headers, ByteArray(0), false
                    )
                )
                raw.size
            }
        }
    }

    private fun parseChunkedBody(
        raw: ByteArray,
        start: Int,
        isRequest: Boolean,
        method: String?,
        path: String?,
        status: Int?,
        httpVersion: String,
        headers: Map<String, String>
    ): Int? {
        var pos = start
        val bodyOut = ByteArrayOutputStream()
        var truncated = false
        while (true) {
            val lineEnd = indexOf(raw, "\r\n".toByteArray(), pos) ?: return null
            val sizeLine = raw.copyOfRange(pos, lineEnd).toString(Charsets.US_ASCII).trim()
            val semi = sizeLine.indexOf(';')
            val sizeHex = if (semi >= 0) sizeLine.substring(0, semi) else sizeLine
            val chunkSize = sizeHex.toIntOrNull(16) ?: return null
            pos = lineEnd + 2
            if (chunkSize == 0) {
                val total = pos + 2 // trailing \r\n after zero chunk
                if (raw.size < total) return null
                val body = bodyOut.toByteArray()
                onMessage(
                    ParsedHttpMessage(
                        isRequest, method, path, status, httpVersion, headers, body, truncated
                    )
                )
                return total
            }
            if (raw.size < pos + chunkSize + 2) return null
            val remaining = maxBodyBytes - bodyOut.size()
            if (remaining > 0) {
                val toCopy = minOf(chunkSize, remaining)
                bodyOut.write(raw, pos, toCopy)
                if (chunkSize > remaining) truncated = true
            } else {
                truncated = true
            }
            pos += chunkSize + 2
        }
    }

    private fun extractBody(raw: ByteArray, start: Int, length: Int): Pair<ByteArray, Boolean> {
        if (length <= maxBodyBytes) {
            return raw.copyOfRange(start, start + length) to false
        }
        return raw.copyOfRange(start, start + maxBodyBytes) to true
    }

    private fun indexOf(raw: ByteArray, needle: ByteArray, from: Int = 0): Int? {
        if (needle.isEmpty() || raw.size < from + needle.size) return null
        outer@ for (i in from..raw.size - needle.size) {
            for (j in needle.indices) {
                if (raw[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}

object MitmHttpFormatter {
    fun bodyToDisplay(body: ByteArray, contentType: String?): String {
        if (body.isEmpty()) return ""
        val ct = contentType?.lowercase() ?: ""
        val asText = when {
            ct.contains("json") || ct.contains("text") || ct.contains("xml") ||
                ct.contains("javascript") || ct.contains("html") || ct.contains("form") -> {
                decodeUtf8(body)
            }
            else -> {
                val decoded = decodeUtf8(body)
                if (decoded.isNotEmpty() && decoded.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?-_=+/@#%&*()[]{}\"'`~^|$\\" }) {
                    decoded
                } else {
                    "[binary ${body.size} bytes]"
                }
            }
        }
        return asText
    }

    private fun decodeUtf8(body: ByteArray): String {
        return try {
            String(body, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun prettyLine(record: MitmTrafficStore.Record): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(record.timestamp))
        return if (record.direction == "REQUEST") {
            val m = record.method ?: "?"
            val host = record.host ?: "?"
            val p = record.path ?: "/"
            "$time ▶ $m  $host$p"
        } else {
            val host = record.host ?: "?"
            val st = record.status?.toString() ?: "?"
            val ct = record.headers["content-type"]?.substringBefore(';')?.trim() ?: "?"
            val size = formatSize(record.bodySize)
            "$time ← $st  $host  $ct  ($size)"
        }
    }

    fun formatSize(bytes: Int): String = when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
