package com.uacspoofer.mobile.vpn

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal class LanHttpError(val status: Int, val reason: String) : IOException(reason)

internal data class LanHttpRequest(
    val host: String,
    val port: Int,
    val tunnel: Boolean,
    val forwardedHead: ByteArray,
    val contentLength: Long,
    val chunked: Boolean,
) {
    fun body(input: InputStream): InputStream =
        if (chunked) LanChunkedBody(input) else LanFixedBody(input, contentLength)
}

/** Parses only the proxy request; HTTPS remains an opaque CONNECT byte stream. */
internal object LanHttpProtocol {
    const val MAX_HEAD_BYTES = 32 * 1024
    private val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val hopHeaders = setOf("connection", "proxy-connection", "keep-alive", "te", "upgrade",
        "proxy-authorization", "proxy-authenticate")
    private val framingHeaders = setOf("host", "content-length", "transfer-encoding")

    fun read(input: InputStream): LanHttpRequest {
        val first = line(input, MAX_HEAD_BYTES)
        var size = first.length + 2
        val parts = first.split(' ')
        if (parts.size != 3 || !token.matches(parts[0])) bad()
        val (method, target, version) = parts
        if (target.isEmpty() || target.any { it <= ' ' || it == '\u007f' || it == '\\' }) bad()
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw LanHttpError(505, "HTTP Version Not Supported")
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val raw = line(input, MAX_HEAD_BYTES - size)
            size += raw.length + 2
            if (raw.isEmpty()) break
            headers.add(header(raw))
        }
        val hosts = headers.filter { it.first == "host" }
        if (hosts.size > 1) bad()
        if (method == "CONNECT") {
            val destination = authority(target, null)
            return LanHttpRequest(destination.first, destination.second, true, byteArrayOf(), 0, false)
        }
        val destination: Pair<String, Int>
        val path: String
        if (target.startsWith('/') || target == "*") {
            destination = authority(hosts.singleOrNull()?.second ?: bad(), 80)
            if (target.startsWith("//") || target.contains('#')) bad()
            path = target
        } else {
            val uri = try { URI(target) } catch (_: Exception) { bad() }
            if (!uri.scheme.equals("http", true) || uri.rawUserInfo != null || uri.rawFragment != null) bad()
            destination = authority(uri.rawAuthority ?: bad(), 80)
            path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        }
        val lengths = headers.filter { it.first == "content-length" }
        val encodings = headers.filter { it.first == "transfer-encoding" }
        if (lengths.size > 1 || encodings.size > 1 || (lengths.isNotEmpty() && encodings.isNotEmpty())) bad()
        val length = lengths.singleOrNull()?.second?.let {
            if (it.isEmpty() || !it.all(Char::isDigit)) bad()
            it.toLongOrNull() ?: bad()
        } ?: 0L
        val chunked = encodings.isNotEmpty()
        if (chunked && !encodings.single().second.equals("chunked", true)) {
            throw LanHttpError(501, "Not Implemented")
        }
        val options = headers.filter { it.first == "connection" || it.first == "proxy-connection" }
            .flatMap { it.second.split(',').map { value -> value.trim().lowercase(Locale.ROOT) } }.toSet()
        if (options.any { !token.matches(it) || it in framingHeaders }) bad()
        val host = if (destination.first.contains(':')) "[${destination.first}]" else destination.first
        val head = buildString {
            append("$method $path $version\r\nHost: $host:${destination.second}\r\n")
            for ((name, value) in headers) {
                if (name != "host" && name !in hopHeaders && name !in options) append("$name: $value\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return LanHttpRequest(destination.first, destination.second, false, head, length, chunked)
    }

    private fun authority(value: String, defaultPort: Int?): Pair<String, Int> {
        if (value.isEmpty() || value.any { it <= ' ' || it in "@/\\?#" }) bad()
        val host: String
        val portText: String?
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            if (end <= 1) bad()
            host = value.substring(1, end)
            if (!host.contains(':') || host.any { it !in "0123456789abcdefABCDEF:." }) bad()
            try { InetAddress.getByName(host) } catch (_: Exception) { bad() }
            val suffix = value.substring(end + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) bad()
            portText = suffix.takeIf { it.isNotEmpty() }?.substring(1)
        } else {
            if (value.count { it == ':' } > 1) bad()
            val colon = value.lastIndexOf(':')
            val rawHost = if (colon >= 0) value.substring(0, colon) else value
            host = try { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES) } catch (_: Exception) { bad() }
            if (host.isEmpty() || host.length > 255) bad()
            portText = if (colon >= 0) value.substring(colon + 1) else null
        }
        val port = if (portText == null) defaultPort ?: bad() else {
            if (portText.isEmpty() || !portText.all { it in '0'..'9' }) bad()
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: bad()
        }
        return host to port
    }

    internal fun header(raw: String): Pair<String, String> {
        val colon = raw.indexOf(':')
        if (colon <= 0 || !token.matches(raw.substring(0, colon))) bad()
        val value = raw.substring(colon + 1).trim(' ', '\t')
        if (value.any { (it < ' ' && it != '\t') || it == '\u007f' }) bad()
        return raw.substring(0, colon).lowercase(Locale.ROOT) to value
    }

    internal fun line(input: InputStream, limit: Int): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException("proxy header EOF")
            if (next == 13) {
                if (input.read() != 10) bad()
                if (bytes.size() + 2 > limit) throw LanHttpError(431, "Request Header Fields Too Large")
                return bytes.toString(Charsets.ISO_8859_1.name())
            }
            if (next == 10 || next == 0) bad()
            if (bytes.size() + 2 >= limit) throw LanHttpError(431, "Request Header Fields Too Large")
            bytes.write(next)
        }
    }

    private fun bad(): Nothing = throw LanHttpError(400, "Bad Request")
}

private class LanFixedBody(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw EOFException("proxy body EOF")
        remaining -= count
        return count
    }
}

/** Preserves chunk framing but never forwards a second pipelined proxy request. */
private class LanChunkedBody(private val input: InputStream) : InputStream() {
    private var pending = byteArrayOf()
    private var position = 0
    private var remaining = 0L
    private var state = 0 // size, data, data CRLF, trailers, done
    private var trailerBytes = 0

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position == pending.size) {
            when (state) {
                0 -> {
                    val line = LanHttpProtocol.line(input, 8192)
                    if (line.any { (it < ' ' && it != '\t') || it == '\u007f' }) throw IOException("chunk extension")
                    val size = line.substringBefore(';')
                    if (size.isEmpty() || !size.all { it in "0123456789abcdefABCDEF" }) throw IOException("chunk size")
                    remaining = size.toLongOrNull(16) ?: throw IOException("chunk overflow")
                    pending = "$line\r\n".toByteArray(Charsets.ISO_8859_1)
                    position = 0
                    state = if (remaining == 0L) 3 else 1
                }
                1 -> {
                    val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("chunk EOF")
                    remaining -= count
                    if (remaining == 0L) state = 2
                    return count
                }
                2 -> {
                    if (input.read() != 13 || input.read() != 10) throw IOException("chunk CRLF")
                    pending = byteArrayOf(13, 10); position = 0; state = 0
                }
                3 -> {
                    val line = LanHttpProtocol.line(input, LanHttpProtocol.MAX_HEAD_BYTES - trailerBytes)
                    trailerBytes += line.length + 2
                    if (line.isEmpty()) state = 4 else {
                        val name = LanHttpProtocol.header(line).first
                        if (name in setOf("host", "content-length", "transfer-encoding", "connection", "proxy-authorization")) {
                            throw IOException("forbidden trailer")
                        }
                    }
                    pending = "$line\r\n".toByteArray(Charsets.ISO_8859_1); position = 0
                }
                else -> return -1
            }
        }
        val count = minOf(length, pending.size - position)
        pending.copyInto(bytes, offset, position, position + count)
        position += count
        return count
    }
}
