package com.uacspoofer.mobile.engine.pow

import android.os.SystemClock
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PowPathProbe {
    private const val HOST = "www.gstatic.com"
    private const val HTTP =
        "GET /generate_204 HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n"

    suspend fun measureMs(
        socksPort: Int,
        timeoutMs: Int = PowQualityPolicy.PROBE_TIMEOUT_MS,
    ): Long = withContext(Dispatchers.IO) {
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
                val host = HOST.toByteArray(Charsets.US_ASCII)
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
                output.write(HTTP.toByteArray(Charsets.US_ASCII))
                output.flush()
                val reply = ByteArray(96)
                val read = input.read(reply)
                if (read < 12) error("http short")
                val text = String(reply, 0, read, Charsets.US_ASCII)
                text.contains(" 204") || text.contains(" 200")
            }
        }.getOrDefault(false)
        if (!ok) return@withContext -1L
        (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
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
