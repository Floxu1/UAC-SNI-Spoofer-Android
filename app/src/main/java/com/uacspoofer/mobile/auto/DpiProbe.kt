package com.uacspoofer.mobile.auto

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Behaviour probe from the desktop first-run wizard: real HTTPS `/cdn-cgi/trace` to Cloudflare edges
 * with SNI cloudflare-ech.com, then the same with a filtered SNI (chatgpt.com) and the ClientHello
 * cut into 8-byte TCP segments. Survives fragmentation -> IP-type DPI; killed -> SNI-type DPI.
 * Sockets are bound to the underlying network, outside the VPN.
 */
class DpiProbe(private val network: Network?) {
    data class Counts(val reachable: Int, val fragOk: Int)

    suspend fun run(ips: List<String> = PROBE_IPS): Counts = coroutineScope {
        val plain = ips.map { ip -> async(Dispatchers.IO) { ip to tlsHttpProbe(ip, OUTER_SNI, 0) } }.awaitAll()
        val good = plain.filter { it.second }.map { it.first }.take(WANT)
        if (good.isEmpty()) return@coroutineScope Counts(0, 0)
        val frag = good.map { ip -> async(Dispatchers.IO) { tlsHttpProbe(ip, FILTERED_SNI, 8) } }.awaitAll()
        Counts(good.size, frag.count { it })
    }

    /** Never throws. [chunk] > 0 splits the first TLS flight into [chunk]-byte segments. */
    internal fun tlsHttpProbe(ip: String, sni: String, chunk: Int, timeoutMs: Int = TIMEOUT_MS): Boolean = runCatching {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.use { s ->
            s.connect(InetSocketAddress(ip, 443), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine(sni, 443)
            engine.useClientMode = true
            engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            exchange(engine, s, chunk, System.currentTimeMillis() + timeoutMs, sni)
        }
    }.getOrDefault(false)

    private fun exchange(engine: SSLEngine, s: Socket, chunk: Int, deadline: Long, sni: String): Boolean {
        val session = engine.session
        val out = s.getOutputStream()
        val input = s.getInputStream()
        var netIn = ByteBuffer.allocate(session.packetBufferSize * 4)
        var appIn = ByteBuffer.allocate(session.applicationBufferSize * 2)
        val netOut = ByteBuffer.allocate(session.packetBufferSize * 2)
        val empty = ByteBuffer.allocate(0)
        val readBuf = ByteArray(16 * 1024)
        var first = true

        fun send(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (bytes.isEmpty()) return
            if (first && chunk > 0) {
                var i = 0
                while (i < bytes.size) {
                    val end = minOf(i + chunk, bytes.size)
                    out.write(bytes, i, end - i)
                    out.flush()
                    Thread.sleep(10)
                    i = end
                }
            } else {
                out.write(bytes)
                out.flush()
            }
            first = false
        }

        fun readMore(): Boolean {
            val n = input.read(readBuf)
            if (n < 0) return false
            if (netIn.remaining() < n) {
                val bigger = ByteBuffer.allocate(netIn.capacity() * 2 + n)
                netIn.flip(); bigger.put(netIn); netIn = bigger
            }
            netIn.put(readBuf, 0, n)
            return true
        }

        fun unwrapOnce(): SSLEngineResult? {
            netIn.flip()
            val r = engine.unwrap(netIn, appIn)
            netIn.compact()
            return when (r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (readMore()) r else null
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val bigger = ByteBuffer.allocate(appIn.capacity() * 2)
                    appIn.flip(); bigger.put(appIn); appIn = bigger
                    r
                }
                SSLEngineResult.Status.CLOSED -> null
                else -> r
            }
        }

        engine.beginHandshake()
        loop@ while (System.currentTimeMillis() < deadline) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val r = engine.wrap(empty, netOut)
                    netOut.flip()
                    send(netOut)
                    if (r.status == SSLEngineResult.Status.CLOSED) return false
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) engine.delegatedTask?.run() ?: break
                }
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, SSLEngineResult.HandshakeStatus.FINISHED -> break@loop
                else -> unwrapOnce() ?: return false
            }
        }
        if (System.currentTimeMillis() >= deadline) return false

        val request = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $sni\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
        netOut.clear()
        engine.wrap(ByteBuffer.wrap(request.toByteArray(Charsets.US_ASCII)), netOut)
        netOut.flip()
        send(netOut)
        while (System.currentTimeMillis() < deadline) {
            if (appIn.position() >= 5) {
                val head = String(appIn.array(), 0, 5, Charsets.US_ASCII)
                return head == "HTTP/"
            }
            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                while (true) engine.delegatedTask?.run() ?: break
                continue
            }
            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                netOut.clear(); engine.wrap(empty, netOut); netOut.flip(); send(netOut)
                continue
            }
            unwrapOnce() ?: return false
        }
        return false
    }

    companion object {
        const val OUTER_SNI = "cloudflare-ech.com"
        const val FILTERED_SNI = "chatgpt.com"
        const val TIMEOUT_MS = 3_000
        const val WANT = 6
        val PROBE_IPS = listOf(
            "188.114.98.0", "188.114.99.0", "104.26.13.100", "104.26.14.40",
            "104.18.8.83", "104.18.9.83", "104.16.132.229", "162.159.36.1",
        )
    }
}