package com.uacspoofer.mobile.engine.pow

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
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock

internal object PowSocksConnectOnly {
    private const val CMD_CONNECT = 0x01
    private const val UPSTREAM_TIMEOUT_MS = 6_000
    // Level C: QoS — small streams (HTML) are prioritized over bulk.
    private const val SMALL_STREAM_THRESHOLD = 32 * 1024
    private const val HIGH_PRIO_TIMEOUT_MS = 3_000
    private const val LOW_PRIO_TIMEOUT_MS = 8_000
    private val COMMAND_NOT_SUPPORTED =
        byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    private val running = AtomicBoolean(false)
    private val connectTimeoutMs = AtomicInteger(UPSTREAM_TIMEOUT_MS)
    private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()
    // QoS counters isolated to PoW relay only.
    private val activeStreams = AtomicInteger(0)

    // Level C: passive quality signal — measured on user traffic, costs zero extra radio.
    private const val PASSIVE_WINDOW = 12
    private val passiveLatencies = ArrayDeque<Long>()
    @Volatile private var lastActivityAt = 0L
    private val passiveFailures = AtomicInteger(0)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var upstreamPort: Int = PowCoreConfig.SOCKS_PORT

    // "Two tunnels at once": SOCKS port of Psiphon tunnel 2, or 0 when it is not up.
    @Volatile
    private var secondaryPort: Int = 0

    fun setSecondaryPort(port: Int) {
        secondaryPort = if (port > 0) port else 0
    }

    private class Plan(val ports: IntArray, val tunnels: IntArray, val group: String)

    /** Host of a SOCKS CONNECT request: the name, or the IPv4/IPv6 literal. */
    private fun requestTarget(request: ByteArray): String? = when (request[3].toInt() and 0xff) {
        0x03 -> requestDomain(request)?.first
        0x01 -> if (request.size >= 8) {
            (4 until 8).joinToString(".") { (request[it].toInt() and 0xff).toString() }
        } else {
            null
        }
        0x04 -> if (request.size >= 20) {
            runCatching { InetAddress.getByAddress(request.copyOfRange(4, 20)).hostAddress }.getOrNull()
        } else {
            null
        }
        else -> null
    }

    /**
     * Which local Psiphon SOCKS port(s) to use. With one tunnel (toggle off, or
     * tunnel 2 never came up) this is just tunnel 1 and nothing else runs.
     * With two, every service is pinned to one tunnel (PowStickyRoute) and the
     * other is only a fallback when the pinned one does not answer locally.
     */
    private fun upstreamPlan(request: ByteArray): Plan? {
        val second = secondaryPort
        if (second <= 0 && !PowStickyRoute.active) return null
        val target = requestTarget(request) ?: return null
        val choice = PowStickyRoute.choose(target, second > 0)
        if ((choice.fresh || choice.moved) && PowStickyRoute.shouldLog()) {
            AppLogRepository.info(
                LogSource.POW,
                "Sticky: $target -> ${choice.group} on tunnel ${choice.tunnel + 1}" +
                    if (choice.moved) " (tunnel 2 down, moved)" else "",
            )
        }
        if (second <= 0) return null
        return if (choice.tunnel == PowStickyRoute.TUNNEL_2) {
            Plan(intArrayOf(second, upstreamPort), intArrayOf(PowStickyRoute.TUNNEL_2, PowStickyRoute.TUNNEL_1), choice.group)
        } else {
            Plan(intArrayOf(upstreamPort, second), intArrayOf(PowStickyRoute.TUNNEL_1, PowStickyRoute.TUNNEL_2), choice.group)
        }
    }

    /** Connect + SOCKS greeting to the first local tunnel that answers, in plan order. */
    private fun connectPlanned(plan: Plan, timeout: Int): Socket {
        var last: Throwable? = null
        for (index in plan.ports.indices) {
            val attempt = Socket()
            track(attempt)
            try {
                attempt.tcpNoDelay = true
                attempt.keepAlive = true
                attempt.connect(InetSocketAddress("127.0.0.1", plan.ports[index]), timeout)
                attempt.soTimeout = timeout
                val out = attempt.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val auth = readExact(attempt.getInputStream(), 2)
                if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) error("upstream socks auth")
                if (index > 0) {
                    PowStickyRoute.moveTo(plan.group, plan.tunnels[index])
                    if (PowStickyRoute.shouldLog()) {
                        AppLogRepository.warning(
                            LogSource.POW,
                            "Sticky: ${plan.group} moved to tunnel ${plan.tunnels[index] + 1} " +
                                "(tunnel ${plan.tunnels[0] + 1} did not answer)",
                        )
                    }
                }
                return attempt
            } catch (error: Throwable) {
                last = error
                untrack(attempt)
                runCatching { attempt.close() }
            }
        }
        throw last ?: IllegalStateException("no upstream")
    }

    // Iranian (.ir) names leave on the phone's own network instead of going
    // through Psiphon. This process is excluded from the VPN
    // (AppRoutingPreferences always disallows our own package), so the socket
    // cannot loop back into the TUN. It never goes through WARP.
    private const val DOMESTIC_CONNECT_TIMEOUT_MS = 4_000
    private val DOMESTIC_SUCCESS = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
    @Volatile private var domesticDirect = true
    private val domesticLogged = AtomicBoolean(false)

    fun setDomesticDirect(enabled: Boolean) {
        domesticDirect = enabled
        domesticLogged.set(false)
    }

    /** True for a host name under the .ir TLD (SOCKS ATYP domain only). */
    fun isDomesticHost(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        return name.length > 3 && name.endsWith(".ir")
    }

    private fun requestDomain(request: ByteArray): Pair<String, Int>? {
        if (request.size < 7 || (request[3].toInt() and 0xff) != 0x03) return null
        val len = request[4].toInt() and 0xff
        if (request.size < 5 + len + 2) return null
        val host = String(request, 5, len, Charsets.US_ASCII)
        val port = ((request[5 + len].toInt() and 0xff) shl 8) or (request[6 + len].toInt() and 0xff)
        return host to port
    }

    // Small resolver cache for the .ir direct route: Android's own InetAddress
    // cache lives ~2s, so repeat page loads re-asked the carrier DNS each time.
    private const val DOMESTIC_DNS_TTL_MS = 120_000L
    private const val DOMESTIC_DNS_MAX = 256
    private class Resolved(val address: InetAddress, val at: Long)
    private val domesticDns = ConcurrentHashMap<String, Resolved>()

    private fun resolveDomestic(host: String): InetAddress {
        val key = host.trim().trimEnd('.').lowercase()
        val now = SystemClock.elapsedRealtime()
        domesticDns[key]?.let { cached ->
            if (now - cached.at < DOMESTIC_DNS_TTL_MS) return cached.address
        }
        val address = InetAddress.getByName(key)
        if (domesticDns.size >= DOMESTIC_DNS_MAX) domesticDns.clear()
        domesticDns[key] = Resolved(address, now)
        return address
    }

    /** Warm the .ir direct-route cache (phone network DNS; never through Psiphon or WARP). */
    fun prefetchDomestic(host: String): Boolean =
        isDomesticHost(host) && runCatching { resolveDomestic(host) }.isSuccess

    private fun connectDomestic(request: ByteArray): Socket? {
        if (!domesticDirect) return null
        val (host, port) = requestDomain(request) ?: return null
        if (!isDomesticHost(host) || port <= 0) return null
        val direct = Socket()
        return try {
            direct.tcpNoDelay = true
            direct.keepAlive = true
            val address = try {
                resolveDomestic(host)
            } catch (error: Throwable) {
                domesticDns.remove(host.trim().trimEnd('.').lowercase())
                throw error
            }
            try {
                direct.connect(InetSocketAddress(address, port), DOMESTIC_CONNECT_TIMEOUT_MS)
            } catch (error: Throwable) {
                // A stale cached address must not pin the failure: forget it so
                // the next attempt resolves again.
                domesticDns.remove(host.trim().trimEnd('.').lowercase())
                throw error
            }
            if (domesticLogged.compareAndSet(false, true)) {
                AppLogRepository.info(LogSource.POW, "Split: $host went direct over the phone network (.ir)")
            }
            direct
        } catch (_: Throwable) {
            runCatching { direct.close() }
            null
        }
    }

    @Synchronized
    fun start(listenPort: Int, targetPort: Int): Boolean {
        stopLocked()
        if (listenPort <= 0 || targetPort <= 0) return false
        upstreamPort = targetPort
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 256)
            }
        } catch (error: Throwable) {
            AppLogRepository.warning(
                LogSource.POW,
                "TCP-only SOCKS listen failed on 127.0.0.1:$listenPort: ${error.message}",
            )
            return false
        }
        server = socket
        running.set(true)
        val thread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    if (!running.get()) break
                    continue
                }
                // Level C: prioritize accept burst with higher priority thread for first bytes
                val prio = if (activeStreams.get() < 24) Thread.NORM_PRIORITY else Thread.MIN_PRIORITY
                Thread({ handle(client) }, "uac-pow-socks-relay").apply {
                    isDaemon = true
                    priority = prio
                    start()
                }
            }
        }, "uac-pow-socks-accept")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
        AppLogRepository.info(
            LogSource.POW,
            "Device TUN SOCKS 127.0.0.1:$listenPort forwards TCP CONNECT to 127.0.0.1:$targetPort",
        )
        return true
    }

    @Synchronized
    fun stop() = stopLocked()

    fun setFailFast(enabled: Boolean) {
        connectTimeoutMs.set(
            if (enabled) PowQualityPolicy.FAIL_FAST_MS else UPSTREAM_TIMEOUT_MS,
        )
    }

    fun dropRelays() {
        liveSockets.toTypedArray().forEach { socket ->
            runCatching { socket.close() }
        }
    }

    // Level C: ghost handover prefers draining over dropping.
    fun drainRelaysGracefully(maxWaitMs: Long = 800L) {
        // Signal half-close to let in-flight HTTP finish, don't RST.
        liveSockets.toTypedArray().forEach { sock ->
            runCatching { sock.soTimeout = 400 }
        }
        runCatching { Thread.sleep(maxWaitMs.coerceIn(200L, 1500L)) }
    }

    /** Passive, radio-free latency samples taken from real user traffic. */
    fun recentLatencies(): List<Long> = synchronized(passiveLatencies) {
        passiveLatencies.toList()
    }

    /** Elapsed ms since the last relayed byte, or Long.MAX_VALUE when never active. */
    fun msSinceLastActivity(): Long {
        val at = lastActivityAt
        return if (at <= 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - at
    }

    fun activeStreamCount(): Int = activeStreams.get()

    /** Called when an upstream connect fails — mirrors a failed quality probe. */
    fun recordPassiveFailure() {
        if (passiveFailures.incrementAndGet() > 4) {
            passiveFailures.set(4)
        }
    }

    private fun recordPassiveLatency(ms: Long) {
        if (ms <= 0L || ms > 60_000L) {
            recordPassiveFailure()
            return
        }
        passiveFailures.set(0)
        lastActivityAt = SystemClock.elapsedRealtime()
        synchronized(passiveLatencies) {
            if (passiveLatencies.size >= PASSIVE_WINDOW) passiveLatencies.removeFirst()
            passiveLatencies.addLast(ms)
        }
    }

    private fun forgetPassiveSamples() {
        synchronized(passiveLatencies) { passiveLatencies.clear() }
    }

    /** Drops samples taken while the tunnel was busy, so they are never judged. */
    fun discardPassiveSamples() = forgetPassiveSamples()

    fun setUpstreamPort(port: Int) {
        if (port > 0) upstreamPort = port
    }

    private fun stopLocked() {
        running.set(false)
        setFailFast(false)
        dropRelays()
        runCatching { server?.close() }
        server = null
        val thread = acceptThread
        acceptThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(500L) }
        }
        liveSockets.clear()
        domesticDns.clear()
        activeStreams.set(0)
        forgetPassiveSamples()
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        track(client)
        activeStreams.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        try {
            val timeout = connectTimeoutMs.get()
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = timeout
            val input = client.getInputStream()
            val output = client.getOutputStream()
            readGreeting(input)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
            val request = readRequest(input)
            requestDomain(request)?.let { PowDnsWarm.note(it.first) }
            if ((request[1].toInt() and 0xff) != CMD_CONNECT) {
                output.write(COMMAND_NOT_SUPPORTED)
                output.flush()
                return
            }
            val domestic = connectDomestic(request)
            if (domestic != null) {
                upstream = domestic
                track(domestic)
                output.write(DOMESTIC_SUCCESS)
                output.flush()
                client.soTimeout = 0
                spliceWithQos(
                    client,
                    domestic,
                    input,
                    output,
                    domestic.getInputStream(),
                    domestic.getOutputStream(),
                    started,
                    measure = false,
                )
                return
            }
            val plan = upstreamPlan(request)
            if (plan != null) {
                val picked = connectPlanned(plan, timeout)
                upstream = picked
                val pickedIn = picked.getInputStream()
                val pickedOut = picked.getOutputStream()
                pickedOut.write(request)
                client.soTimeout = 0
                picked.soTimeout = 0
                spliceWithQos(client, picked, input, output, pickedIn, pickedOut, started)
                return
            }
            val remote = Socket()
            upstream = remote
            track(remote)
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.connect(InetSocketAddress("127.0.0.1", upstreamPort), timeout)
            remote.soTimeout = timeout
            val upIn = remote.getInputStream()
            val upOut = remote.getOutputStream()
            upOut.write(byteArrayOf(0x05, 0x01, 0x00))
            upOut.flush()
            val auth = readExact(upIn, 2)
            if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) {
                output.write(COMMAND_NOT_SUPPORTED)
                output.flush()
                return
            }
            upOut.write(request)
            client.soTimeout = 0
            remote.soTimeout = 0
            spliceWithQos(client, remote, input, output, upIn, upOut, started)
        } catch (_: Throwable) {
            recordPassiveFailure()
        } finally {
            // Every exit path (normal end, early return, failure) releases the
            // stream exactly once; the count used to only grow.
            activeStreams.updateAndGet { if (it > 0) it - 1 else 0 }
            untrack(upstream)
            untrack(client)
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun track(socket: Socket) {
        liveSockets.add(socket)
    }

    private fun untrack(socket: Socket?) {
        if (socket != null) liveSockets.remove(socket)
    }

    private fun readGreeting(input: InputStream) {
        val head = readExact(input, 2)
        if (head[0] != 0x05.toByte()) error("socks version")
        val methods = head[1].toInt() and 0xff
        if (methods > 0) readExact(input, methods)
    }

    private fun readRequest(input: InputStream): ByteArray {
        val head = readExact(input, 4)
        val extra = when (head[3].toInt() and 0xff) {
            0x01 -> readExact(input, 6)
            0x04 -> readExact(input, 18)
            0x03 -> {
                val len = readExact(input, 1)
                len + readExact(input, (len[0].toInt() and 0xff) + 2)
            }
            else -> error("socks atyp")
        }
        return head + extra
    }
    private fun spliceWithQos(
        client: Socket,
        upstream: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upIn: InputStream,
        upOut: OutputStream,
        startedMs: Long,
        measure: Boolean = true,
    ) {
        val upload = Thread({
            runCatching {
                val buf = ByteArray(16 * 1024)
                var n: Int
                while (clientIn.read(buf).also { n = it } >= 0) {
                    upOut.write(buf, 0, n); upOut.flush()
                }
            }
            runCatching { upstream.shutdownOutput() }
        }, "uac-pow-socks-up")
        upload.isDaemon = true
        upload.start()
        runCatching {
            val buf = ByteArray(16 * 1024)
            var n: Int
            var firstByteRecorded = false
            while (upIn.read(buf).also { n = it } >= 0) {
                if (!firstByteRecorded) {
                    firstByteRecorded = true
                    if (measure) recordPassiveLatency(SystemClock.elapsedRealtime() - startedMs)
                }
                clientOut.write(buf, 0, n); clientOut.flush()
            }
        }
        runCatching { client.shutdownOutput() }
        // The caller closes both sockets next; give a half-closed upload time to finish.
        runCatching { upload.join(5_000L) }
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
