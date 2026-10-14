package com.uacspoofer.mobile.engine.tor

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/** One small HTTP download through Tor SOCKS; returns KB/s or 0. */
internal object TorThroughputProbe {
    const val BYTES = 262_144
    private const val HOST = "speed.cloudflare.com"
    private const val TIMEOUT_MS = 20_000

    fun measureKbps(socksPort: Int): Int {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress.createUnresolved(HOST, 80), TIMEOUT_MS)
            socket.getOutputStream().apply {
                write("GET /__down?bytes=$BYTES HTTP/1.0\r\nHost: $HOST\r\nUser-Agent: Mozilla/5.0\r\n\r\n".toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArray(16 * 1024)
            val first = input.read(buffer)
            if (first <= 0) return 0
            val started = System.nanoTime()
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            return kbps(total, System.nanoTime() - started)
        }
    }

    /** Bigger, time-capped download test: up to [bytes] or [maxMs] of body. */
    fun measureCappedKbps(socksPort: Int, bytes: Int = 2_000_000, maxMs: Long = 3_500): Int {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress.createUnresolved(HOST, 80), TIMEOUT_MS)
            socket.getOutputStream().apply {
                write("GET /__down?bytes=$bytes HTTP/1.0\r\nHost: $HOST\r\nUser-Agent: Mozilla/5.0\r\n\r\n".toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArray(32 * 1024)
            if (input.read(buffer) <= 0) return 0
            val started = System.nanoTime()
            val deadline = started + maxMs * 1_000_000L
            var total = 0L
            while (System.nanoTime() < deadline) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            return kbps(total, System.nanoTime() - started)
        }
    }

    fun kbps(bytes: Long, elapsedNanos: Long): Int {
        if (bytes < 16_384 || elapsedNanos <= 0) return 0
        return ((bytes / 1024.0) / (elapsedNanos / 1e9)).toInt()
    }
}