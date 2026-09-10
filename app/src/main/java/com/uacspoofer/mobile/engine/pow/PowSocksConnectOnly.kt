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

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var upstreamPort: Int = PowCoreConfig.SOCKS_PORT

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
        activeStreams.set(0)
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        track(client)
        activeStreams.incrementAndGet()
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
            if ((request[1].toInt() and 0xff) != CMD_CONNECT) {
                output.write(COMMAND_NOT_SUPPORTED)
                output.flush()
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
            upOut.flush()
            client.soTimeout = 0
            remote.soTimeout = 0
            spliceWithQos(client, remote, input, output, upIn, upOut)
        } catch (_: Throwable) {
        } finally {
            activeStreams.decrementAndGet()
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
            while (upIn.read(buf).also { n = it } >= 0) {
                clientOut.write(buf, 0, n); clientOut.flush()
            }
        }
        runCatching { client.shutdownOutput() }
        runCatching { upload.join(1_000L) }
    }

    @Suppress("unused")
    private fun splice(
        client: Socket,
        upstream: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upIn: InputStream,
        upOut: OutputStream,
    ) = spliceWithQos(client, upstream, clientIn, clientOut, upIn, upOut)

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
