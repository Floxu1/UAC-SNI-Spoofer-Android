package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.settings.LanShareMode
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Per-listener resources prevent a mode switch from reusing an old connection's state. */
internal class LanShareServer(
    listenPort: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val mode: LanShareMode,
    private val protect: ((Socket) -> Boolean)? = null,
) : AutoCloseable {
    private val gate = Any()
    private val sockets = mutableSetOf<Socket>()
    private var streams = 0
    @Volatile private var closed = false
    private val listener = ServerSocket().apply {
        reuseAddress = true
        try { bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), listenPort), 32) }
        catch (error: Exception) { close(); throw error }
    }
    private val acceptThread = Thread(::acceptLoop, "uac-lan-share").apply { isDaemon = true }
    val port: Int get() = listener.localPort
    val isRunning: Boolean get() = !closed && !listener.isClosed && acceptThread.isAlive
    internal val activeConnections: Int get() = synchronized(gate) { streams }

    fun start(): LanShareServer {
        try { acceptThread.start() } catch (error: Throwable) { close(); throw error }
        return this
    }

    override fun close() {
        val snapshot = synchronized(gate) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        runCatching { listener.close() }
        snapshot.forEach { runCatching { it.close() } }
        if (Thread.currentThread() !== acceptThread) runCatching { acceptThread.join(400) }
    }

    private fun acceptLoop() {
        while (!closed && !listener.isClosed) {
            val client = try { listener.accept() } catch (_: IOException) { break }
            val admitted = synchronized(gate) {
                if (closed || streams >= LanSharePolicy.MAX_STREAMS ||
                    !LanSharePolicy.acceptClient(client.inetAddress?.hostAddress)) false
                else { streams++; sockets.add(client); true }
            }
            if (!admitted) { runCatching { client.close() }; continue }
            runCatching {
                Thread({ handle(client) }, "uac-lan-share-stream").apply { isDaemon = true }.start()
            }.onFailure { release(client, null); runCatching { client.close() } }
        }
        close()
    }

    private fun register(socket: Socket): Boolean = synchronized(gate) {
        if (closed) false else { sockets.add(socket); true }
    }

    private fun release(client: Socket, upstream: Socket?) = synchronized(gate) {
        sockets.remove(client)
        if (upstream != null) sockets.remove(upstream)
        streams--
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var responseStarted = false
        var socksAccepted = false
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val http = if (mode == LanShareMode.PLAYSTATION_5) LanHttpProtocol.read(input) else null
            val request = if (http != null) {
                LanShareSocks.request(http.host, http.port)
            } else {
                val bytes = LanShareSocks.readClient(input, output) ?: return
                socksAccepted = true
                if (bytes[1] != 1.toByte()) {
                    output.write(LanShareSocks.failure(7)); output.flush(); return
                }
                bytes
            }
            val remote = Socket()
            upstream = remote
            if (!register(remote)) return
            protect?.invoke(remote)
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.connect(InetSocketAddress(upstreamHost, upstreamPort), HANDSHAKE_TIMEOUT_MS)
            remote.soTimeout = HANDSHAKE_TIMEOUT_MS
            val upIn = BufferedInputStream(remote.getInputStream())
            val upOut = remote.getOutputStream()
            val reply = LanShareSocks.connect(upIn, upOut, request)
            if (http == null) {
                output.write(reply); output.flush(); responseStarted = true
                if (reply[1] != 0.toByte()) return
            } else {
                if (reply[1] != 0.toByte()) throw IOException("upstream refused target")
                if (http.tunnel) {
                    output.write(CONNECT_OK); output.flush(); responseStarted = true
                } else {
                    upOut.write(http.forwardedHead); upOut.flush(); responseStarted = true
                }
            }
            client.soTimeout = 0
            remote.soTimeout = 0
            val body = if (http != null && !http.tunnel) http.body(input) else input
            splice(client, remote, body, output, upIn, upOut)
        } catch (error: Exception) {
            if (!responseStarted) runCatching {
                if (mode == LanShareMode.PLAYSTATION_5) {
                    val status = (error as? LanHttpError)?.status ?: 502
                    val reason = (error as? LanHttpError)?.reason ?: "Bad Gateway"
                    client.getOutputStream().write("HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                } else if (socksAccepted) client.getOutputStream().write(LanShareSocks.failure(1))
            }
        } catch (_: OutOfMemoryError) {
            // Keep the service alive if a trusted client's relay thread cannot allocate.
        } finally {
            release(client, upstream)
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun splice(client: Socket, upstream: Socket, input: InputStream, output: OutputStream,
                       upIn: InputStream, upOut: OutputStream) {
        val upload = Thread({
            runCatching { copy(input, upOut) }
            runCatching { upstream.shutdownOutput() }
        }, "uac-lan-share-up").apply { isDaemon = true }
        upload.start()
        runCatching { copy(upIn, output) }
        runCatching { client.shutdownOutput() }
        runCatching { upload.join(1_000) }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count > 0) output.write(buffer, 0, count)
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private val CONNECT_OK = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}

internal object LanShareSocks {
    fun failure(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    fun readClient(input: InputStream, output: OutputStream): ByteArray? {
        val greeting = exact(input, 2)
        if (greeting[0] != 5.toByte()) throw IOException("SOCKS version")
        val methods = exact(input, greeting[1].toInt() and 255)
        val accepted = methods.any { it == 0.toByte() }
        output.write(byteArrayOf(5, if (accepted) 0 else 0xff.toByte())); output.flush()
        if (!accepted) return null
        val head = exact(input, 4)
        if (head[0] != 5.toByte() || head[2] != 0.toByte()) throw IOException("SOCKS request")
        return head + address(input, head[3].toInt() and 255)
    }

    fun request(host: String, port: Int): ByteArray {
        val address = if (host.contains(':')) {
            val bytes = InetAddress.getByName(host).address
            byteArrayOf(if (bytes.size == 16) 4 else 1) + bytes
        } else {
            val bytes = host.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..255)
            byteArrayOf(3, bytes.size.toByte()) + bytes
        }
        return byteArrayOf(5, 1, 0) + address + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    fun connect(input: InputStream, output: OutputStream, request: ByteArray): ByteArray {
        output.write(byteArrayOf(5, 1, 0)); output.flush()
        if (!exact(input, 2).contentEquals(byteArrayOf(5, 0))) throw IOException("upstream auth")
        output.write(request); output.flush()
        val head = exact(input, 4)
        if (head[0] != 5.toByte() || head[2] != 0.toByte()) throw IOException("upstream SOCKS reply")
        return head + address(input, head[3].toInt() and 255)
    }

    private fun address(input: InputStream, type: Int): ByteArray = when (type) {
        1 -> exact(input, 6)
        4 -> exact(input, 18)
        3 -> exact(input, 1).let { length ->
            val count = length[0].toInt() and 255
            if (count == 0) throw IOException("SOCKS domain length")
            length + exact(input, count + 2)
        }
        else -> throw IOException("SOCKS address type")
    }

    private fun exact(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("SOCKS EOF")
            offset += read
        }
        return bytes
    }
}
