package com.uacspoofer.mobile.engine.pow

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A bounded single-stream download through the final Psiphon SOCKS proxy. Startup only. */
internal object PowStartupDownloadProbe {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val WARMUP_BYTES = 128 * 1024
    private const val WINDOW_MS = 2_000L
    private const val TOTAL_MS = 7_000L

    suspend fun measure(
        port: Int,
        target: URL? = null,
        diagnostic: (String) -> Unit = {},
    ): PowStartupSpeedPicker.Sample? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val opened = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - opened) / 1_000_000
        val url = target ?: URL("https://speed.cloudflare.com/__down?bytes=$MAX_BYTES&uac=$opened")
        val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        // Closing the actual socket bounds DNS/SOCKS, TLS, headers, and body reads alike.
        val deadline = Timer("pow-startup-download-deadline", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { runCatching { socket.close() } }
        }, TOTAL_MS)
        var stream: Socket = socket
        try {
            context.ensureActive()
            val remotePort = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
            socket.soTimeout = 2_000
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress.createUnresolved(url.host, remotePort), 3_000)
            if (url.protocol == "https") {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, url.host, remotePort, true) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.soTimeout = 2_000
                tls.startHandshake()
                stream = tls
            }
            context.ensureActive()
            val path = url.file.ifEmpty { "/" }
            stream.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nHost: ${url.host}\r\n" +
                    "Accept-Encoding: identity\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = BufferedInputStream(stream.getInputStream(), 32 * 1024)
            val status = readLine(input)
            if (status.split(' ').getOrNull(1) != "200") return@withContext null
            val headers = mutableMapOf<String, String>()
            var headerBytes = status.length
            while (true) {
                context.ensureActive()
                val line = readLine(input)
                headerBytes += line.length + 2
                check(headerBytes <= 32 * 1024) { "oversized HTTP headers" }
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
            }
            check(headers["content-encoding"].orEmpty() in listOf("", "identity")) { "compressed speed response" }
            val body = if (headers["transfer-encoding"].orEmpty().contains("chunked", true)) ChunkedBody(input) else input
            val declared = headers["content-length"]?.toLongOrNull() ?: MAX_BYTES.toLong()
            val limit = minOf(MAX_BYTES.toLong(), declared).toInt()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            var warmBytes = 0
            var warmAt = -1L
            var firstByteMs = -1L
            while (total < limit && elapsedMs() < TOTAL_MS) {
                context.ensureActive()
                if (warmAt >= 0 && (System.nanoTime() - warmAt) / 1_000_000 >= WINDOW_MS) break
                val count = try { body.read(buffer, 0, minOf(buffer.size, limit - total)) }
                    catch (_: SocketTimeoutException) { break }
                if (count < 0) break
                if (firstByteMs < 0) firstByteMs = elapsedMs()
                total += count
                if (warmAt < 0 && total >= WARMUP_BYTES) {
                    warmAt = System.nanoTime()
                    warmBytes = total
                }
            }
            val nanos = System.nanoTime() - warmAt
            val bytes = total - warmBytes
            if (warmAt < 0 || bytes < 128 * 1024 || nanos < 100_000_000 || firstByteMs < 0) {
                diagnostic("insufficient sample: bytes=$bytes first_ms=$firstByteMs")
                null
            } else PowStartupSpeedPicker.Sample(bytes.toLong() * 8 * 1_000_000_000 / nanos, firstByteMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            context.ensureActive()
            diagnostic("${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            deadline.cancel()
            runCatching { stream.close() }
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (line.length <= 8 * 1024) {
            val value = input.read()
            check(value >= 0) { "truncated HTTP response" }
            if (value == 10) return line.toString().removeSuffix("\r")
            line.append(value.toChar())
        }
        error("oversized HTTP line")
    }

    internal class ChunkedBody(private val input: InputStream) : InputStream() {
        private var remaining = 0L
        private var consumeCrLf = false
        private var ended = false
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (ended) return -1
            if (remaining == 0L) {
                if (consumeCrLf) check(readLine(input).isEmpty()) { "invalid chunk terminator" }
                remaining = readLine(input).substringBefore(';').trim().toLong(16)
                check(remaining >= 0) { "invalid chunk size" }
                if (remaining == 0L) { ended = true; return -1 }
                consumeCrLf = true
            }
            val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            check(count > 0) { "truncated HTTP chunk" }
            remaining -= count
            return count
        }
    }
}
