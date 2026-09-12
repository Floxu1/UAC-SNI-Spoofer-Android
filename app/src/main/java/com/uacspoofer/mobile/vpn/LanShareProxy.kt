package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.PhoneImportLan
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOCKS5 CONNECT relay bound on the LAN. Upstream stays on 127.0.0.1 so engine
 * SOCKS ports are never opened to the network.
 */
object LanShareProxy {
    private const val CMD_CONNECT = 0x01
    private const val HANDSHAKE_TIMEOUT_MS = 8_000
    private val COMMAND_NOT_SUPPORTED = byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    private val running = AtomicBoolean(false)
    private val activeStreams = AtomicInteger(0)
    private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var upstreamHost = "127.0.0.1"
    @Volatile private var upstreamPort = 0
    @Volatile private var protectSocket: ((Socket) -> Boolean)? = null

    val isRunning: Boolean get() = running.get() && server?.isClosed == false

    @Synchronized
    fun start(
        listenPort: Int = LanSharePolicy.PORT,
        upstreamHost: String,
        upstreamPort: Int,
        protect: ((Socket) -> Boolean)? = null,
    ): LanShareEndpoint {
        stopLocked()
        if (upstreamPort <= 0) {
            val endpoint = LanShareEndpoint(detail = "upstream unavailable")
            LanShareStatus.update(endpoint)
            return endpoint
        }
        val lanIp = PhoneImportLan.pickLanIpv4()
        if (lanIp.isNullOrBlank()) {
            val endpoint = LanShareEndpoint(detail = "no lan")
            LanShareStatus.update(endpoint)
            return endpoint
        }
        this.upstreamHost = upstreamHost
        this.upstreamPort = upstreamPort
        this.protectSocket = protect
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), listenPort), 32)
        server = listener
        running.set(true)
        val thread = Thread({ acceptLoop(listener) }, "uac-lan-share")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
        val endpoint = LanShareEndpoint(
            listening = true,
            address = lanIp,
            port = listenPort,
        )
        LanShareStatus.update(endpoint)
        AppLogRepository.info(
            LogSource.SERVICE,
            "LAN share SOCKS5 ${endpoint.label} → 127.0.0.1:$upstreamPort",
        )
        return endpoint
    }

    @Synchronized
    fun stop() = stopLocked()

    fun refreshAdvertisedAddress() {
        val listener = server ?: return
        if (!running.get() || listener.isClosed) return
        val lanIp = PhoneImportLan.pickLanIpv4()
        if (lanIp.isNullOrBlank()) {
            stop()
            return
        }
        val current = LanShareStatus.endpoint.value
        if (current.address != lanIp) {
            LanShareStatus.update(current.copy(address = lanIp, listening = true))
        }
    }

    private fun stopLocked() {
        running.set(false)
        liveSockets.toTypedArray().forEach { socket ->
            runCatching { socket.close() }
        }
        liveSockets.clear()
        activeStreams.set(0)
        runCatching { server?.close() }
        server = null
        val thread = acceptThread
        acceptThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(400L) }
        }
        protectSocket = null
        LanShareStatus.clear()
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get() && !listener.isClosed) {
            val client = runCatching { listener.accept() }.getOrNull() ?: continue
            if (!running.get()) {
                runCatching { client.close() }
                break
            }
            val ip = client.inetAddress?.hostAddress
            if (!LanSharePolicy.acceptClient(ip)) {
                AppLogRepository.warning(LogSource.SERVICE, "LAN share rejected client $ip")
                runCatching { client.close() }
                continue
            }
            if (activeStreams.get() >= LanSharePolicy.MAX_STREAMS) {
                runCatching { client.close() }
                continue
            }
            val worker = Thread({ handle(client) }, "uac-lan-share-stream")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        track(client)
        activeStreams.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
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
            protectSocket?.invoke(remote)
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.connect(InetSocketAddress(upstreamHost, upstreamPort), HANDSHAKE_TIMEOUT_MS)
            remote.soTimeout = HANDSHAKE_TIMEOUT_MS
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
            splice(client, remote, input, output, upIn, upOut)
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

    private fun splice(
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
                    upOut.write(buf, 0, n)
                    upOut.flush()
                }
            }
            runCatching { upstream.shutdownOutput() }
        }, "uac-lan-share-up")
        upload.isDaemon = true
        upload.start()
        runCatching {
            val buf = ByteArray(16 * 1024)
            var n: Int
            while (upIn.read(buf).also { n = it } >= 0) {
                clientOut.write(buf, 0, n)
                clientOut.flush()
            }
        }
        runCatching { client.shutdownOutput() }
        runCatching { upload.join(1_000L) }
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
