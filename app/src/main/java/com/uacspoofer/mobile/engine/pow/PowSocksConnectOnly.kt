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
    private const val UPSTREAM_TIMEOUT_MS = 8_000
    private val COMMAND_NOT_SUPPORTED =
        byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    private val running = AtomicBoolean(false)
    private val connectTimeoutMs = AtomicInteger(UPSTREAM_TIMEOUT_MS)
    private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()

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
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128)
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
                Thread({ handle(client) }, "uac-pow-socks-relay").apply {
                    isDaemon = true
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
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        track(client)
        try {
            val timeout = connectTimeoutMs.get()
            client.tcpNoDelay = true
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
            splice(client, remote, input, output, upIn, upOut)
        } catch (_: Throwable) {
        } finally {
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
            runCatching { clientIn.copyTo(upOut) }
            runCatching { upstream.shutdownOutput() }
        }, "uac-pow-socks-up")
        upload.isDaemon = true
        upload.start()
        runCatching { upIn.copyTo(clientOut) }
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
