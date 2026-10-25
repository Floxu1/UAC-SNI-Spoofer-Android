package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock

/**
 * SOCKS5 CONNECT-only split used by Tor (and reusable): Iranian hosts leave on the
 * phone network; everything else is forwarded to [upstreamPort] (Tor / proxy).
 */
internal object DomesticSocksSplit {
    private const val CMD_CONNECT = 0x01
    private const val UPSTREAM_TIMEOUT_MS = 8_000
    private const val DOMESTIC_TIMEOUT_MS = 4_000
    private val OK = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
    private val CMD_NOT_SUPPORTED = byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    private val running = AtomicBoolean(false)
    private val live = ConcurrentHashMap.newKeySet<Socket>()
    private val logged = AtomicBoolean(false)
    @Volatile private var trafficPaused = false

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var upstreamPort: Int = 0

    @Synchronized
    fun start(listenPort: Int, upstreamSocksPort: Int): Boolean {
        stopLocked()
        if (listenPort <= 0 || upstreamSocksPort <= 0 || listenPort == upstreamSocksPort) return false
        upstreamPort = upstreamSocksPort
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128)
            }
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.TOR, "DomesticSocksSplit bind failed on $listenPort", error)
            return false
        }
        server = socket
        running.set(true)
        logged.set(false)
        acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                Thread({ handle(client) }, "domestic-split").apply { isDaemon = true; start() }
            }
        }, "domestic-split-accept").apply { isDaemon = true; start() }
        AppLogRepository.info(LogSource.TOR, "DomesticSocksSplit listen=$listenPort upstream=$upstreamSocksPort")
        return true
    }

    fun setTrafficPaused(paused: Boolean) {
        trafficPaused = paused
        logged.set(false)
        if (paused) {
            val n = live.size
            live.forEach { runCatching { it.close() } }
            live.clear()
            if (n > 0) {
                AppLogRepository.info(LogSource.TOR, "Pause: dropped $n live sockets (direct-only)")
            }
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread = null
        live.forEach { runCatching { it.close() } }
        live.clear()
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = UPSTREAM_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val greet = readExact(input, 2)
            if (greet[0] != 0x05.toByte()) return
            val nMethods = greet[1].toInt() and 0xff
            if (nMethods > 0) readExact(input, nMethods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
            val head = readExact(input, 4)
            if (head[0] != 0x05.toByte() || head[1].toInt() != CMD_CONNECT) {
                output.write(CMD_NOT_SUPPORTED)
                output.flush()
                return
            }
            val rest = readAddress(input, head[3].toInt() and 0xff)
            val request = head + rest
            val domestic = if (trafficPaused) connectPausedDirect(request) else connectDomestic(request)
            if (domestic != null) {
                upstream = domestic
                track(domestic)
                if (logged.compareAndSet(false, true)) {
                    val msg = if (trafficPaused) {
                        "Pause: traffic going direct (not through Tor)"
                    } else {
                        "Split: Iranian host went direct over the phone network"
                    }
                    AppLogRepository.info(LogSource.TOR, msg)
                }
                output.write(OK)
                output.flush()
                pipe(client, upstream!!)
            } else if (trafficPaused) {
                // Hard stop: never fall through to Tor while paused.
                AppLogRepository.warning(LogSource.TOR, "Pause: direct failed; refusing Tor upstream")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
            } else {
                val up = connectUpstream(request)
                upstream = up
                track(up)
                output.write(OK)
                output.flush()
                pipe(client, up)
            }
        } catch (_: Throwable) {
        } finally {
            upstream?.let { untrack(it); runCatching { it.close() } }
            runCatching { client.close() }
        }
    }



    private fun connectPausedDirect(request: ByteArray): Socket? {
        val atyp = request.getOrNull(3)?.toInt()?.and(0xff) ?: return null
        val direct = Socket()
        try {
            direct.tcpNoDelay = true
            when (atyp) {
                0x03 -> {
                    val hp = domainHostPort(request) ?: run {
                        runCatching { direct.close() }
                        return null
                    }
                    direct.connect(InetSocketAddress(hp.first, hp.second), DOMESTIC_TIMEOUT_MS)
                }
                0x01 -> {
                    if (request.size < 10) {
                        runCatching { direct.close() }
                        return null
                    }
                    val ip = java.net.InetAddress.getByAddress(request.copyOfRange(4, 8))
                    val raw = ip.address
                    if (raw.size == 4) {
                        val a0 = raw[0].toInt() and 0xff
                        val a1 = raw[1].toInt() and 0xff
                        // hev mapdns 100.64/10 and Tor MAP_DNS 198.18/15 are not real hosts.
                        if ((a0 == 100 && a1 in 64..127) || (a0 == 198 && a1 in 18..19)) {
                            runCatching { direct.close() }
                            return null
                        }
                    }
                    val port = ((request[8].toInt() and 0xff) shl 8) or (request[9].toInt() and 0xff)
                    direct.connect(InetSocketAddress(ip, port), DOMESTIC_TIMEOUT_MS)
                }
                else -> {
                    runCatching { direct.close() }
                    return null
                }
            }
            if (logged.compareAndSet(false, true)) {
                AppLogRepository.info(LogSource.TOR, "Pause: traffic going direct (not through Tor)")
            }
            return direct
        } catch (_: Throwable) {
            runCatching { direct.close() }
            return null
        }
    }

    private fun connectDomestic(request: ByteArray): Socket? {
        if (!DomesticDirect.enabled) return null
        val hostPort = domainHostPort(request) ?: return null
        if (!DomesticDirect.isDomesticHost(hostPort.first) || hostPort.second <= 0) return null
        val direct = Socket()
        return try {
            direct.tcpNoDelay = true
            direct.connect(InetSocketAddress(hostPort.first, hostPort.second), DOMESTIC_TIMEOUT_MS)
            direct
        } catch (_: Throwable) {
            runCatching { direct.close() }
            null
        }
    }

    private fun connectUpstream(request: ByteArray): Socket {
        val up = Socket()
        up.tcpNoDelay = true
        up.connect(InetSocketAddress("127.0.0.1", upstreamPort), UPSTREAM_TIMEOUT_MS)
        up.soTimeout = UPSTREAM_TIMEOUT_MS
        val out = up.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val auth = readExact(up.getInputStream(), 2)
        if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) error("upstream socks auth")
        out.write(request)
        out.flush()
        // Consume SOCKS reply
        val repHead = readExact(up.getInputStream(), 4)
        readAddress(up.getInputStream(), repHead[3].toInt() and 0xff)
        if (repHead[1].toInt() != 0) error("upstream socks rejected")
        return up
    }

    private fun domainHostPort(request: ByteArray): Pair<String, Int>? {
        if (request.size < 7 || (request[3].toInt() and 0xff) != 0x03) return null
        val len = request[4].toInt() and 0xff
        if (request.size < 5 + len + 2) return null
        val host = String(request, 5, len, Charsets.US_ASCII)
        val port = ((request[5 + len].toInt() and 0xff) shl 8) or (request[6 + len].toInt() and 0xff)
        return host to port
    }

    private fun readAddress(input: InputStream, atyp: Int): ByteArray = when (atyp) {
        0x01 -> readExact(input, 4 + 2)
        0x03 -> {
            val len = readExact(input, 1)
            len + readExact(input, (len[0].toInt() and 0xff) + 2)
        }
        0x04 -> readExact(input, 16 + 2)
        else -> error("bad atyp")
    }

    private fun pipe(a: Socket, b: Socket) {
        val t1 = Thread({ copy(a.getInputStream(), b.getOutputStream()) }, "split-up").apply { isDaemon = true; start() }
        try {
            copy(b.getInputStream(), a.getOutputStream())
        } finally {
            runCatching { a.shutdownInput() }
            runCatching { b.shutdownOutput() }
            runCatching { t1.join(500) }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun track(s: Socket) { live.add(s) }
    private fun untrack(s: Socket) { live.remove(s) }

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
