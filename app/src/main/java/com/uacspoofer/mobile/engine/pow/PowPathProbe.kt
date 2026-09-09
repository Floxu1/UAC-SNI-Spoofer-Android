package com.uacspoofer.mobile.engine.pow

import android.os.SystemClock
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PowPathProbe {
    private data class ProbeTarget(val host: String, val http: String)

    private val TARGETS = listOf(
        ProbeTarget(
            "www.gstatic.com",
            "GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n",
        ),
        ProbeTarget(
            "cloudflare.com",
            "GET /cdn-cgi/trace HTTP/1.1\r\nHost: cloudflare.com\r\nConnection: close\r\n\r\n",
        ),
        ProbeTarget(
            "www.google.com",
            "GET /generate_204 HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n",
        ),
    )

    // Fallback single-target read length for the 8KB download phase.
    private const val EXTRA_READ_BYTES = 8 * 1024

    suspend fun measureMs(
        socksPort: Int,
        timeoutMs: Int = PowQualityPolicy.PROBE_TIMEOUT_MS,
    ): Long = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val perTargetTimeout = (timeoutMs / TARGETS.size).coerceAtLeast(700)
        val results = TARGETS.map { target ->
            // Each target is probed independently; the overall verdict is 2/3 success.
            measureSingleTargetMs(socksPort, perTargetTimeout, target)
        }
        val successes = results.filter { it > 0L }.sorted()
        if (successes.size < 2) return@withContext -1L
        // TTFB + small download: median of successful targets captures real browsing latency.
        val median = successes[successes.size / 2]
        // Include wall time for the parallel batch but cap by median to avoid outlier inflation.
        val wall = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        // If all probes ran in sequence the wall equals sum; prefer median for stability.
        minOf(median, wall).coerceAtLeast(1L)
    }

    private fun measureSingleTargetMs(
        socksPort: Int,
        timeoutMs: Int,
        target: ProbeTarget,
    ): Long {
        val started = SystemClock.elapsedRealtime()
        val ok = runCatching {
            Socket().use { sock ->
                sock.tcpNoDelay = true
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val auth = readExact(input, 2)
                if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) {
                    error("socks auth")
                }
                val host = target.host.toByteArray(Charsets.US_ASCII)
                val request = ByteArray(7 + host.size)
                request[0] = 0x05
                request[1] = 0x01
                request[2] = 0x00
                request[3] = 0x03
                request[4] = host.size.toByte()
                System.arraycopy(host, 0, request, 5, host.size)
                val portAt = 5 + host.size
                request[portAt] = 0x00
                request[portAt + 1] = 0x50
                output.write(request)
                output.flush()
                val head = readExact(input, 4)
                if (head[1] != 0x00.toByte()) error("socks connect")
                when (head[3].toInt() and 0xff) {
                    0x01 -> readExact(input, 6)
                    0x04 -> readExact(input, 18)
                    0x03 -> {
                        val len = readExact(input, 1)
                        readExact(input, (len[0].toInt() and 0xff) + 2)
                    }
                    else -> error("socks atyp")
                }
                output.write(target.http.toByteArray(Charsets.US_ASCII))
                output.flush()
                // Read header + up to 8KB body to measure real download, not just header.
                val buffer = ByteArray(96 + EXTRA_READ_BYTES)
                var totalRead = 0
                var firstChunkMs = -1L
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (totalRead < 96 && SystemClock.elapsedRealtime() < deadline) {
                    val n = input.read(buffer, totalRead, 96 - totalRead)
                    if (n <= 0) break
                    if (firstChunkMs < 0) firstChunkMs = SystemClock.elapsedRealtime() - started
                    totalRead += n
                    if (totalRead >= 12) {
                        val text = String(buffer, 0, totalRead, Charsets.US_ASCII)
                        if (text.contains(" 204") || text.contains(" 200")) break
                    }
                }
                if (totalRead < 12) error("http short")
                val text = String(buffer, 0, totalRead, Charsets.US_ASCII)
                if (!text.contains(" 204") && !text.contains(" 200")) error("http status")
                // Try to drain a bit more to account for throughput, but don't fail if not available.
                runCatching {
                    sock.soTimeout = 300
                    val extra = ByteArray(EXTRA_READ_BYTES)
                    input.read(extra)
                }
                true
            }
        }.getOrDefault(false)
        if (!ok) return -1L
        return (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read <= 0) error("socks eof")
            offset += read
        }
        return buffer
    }
}
