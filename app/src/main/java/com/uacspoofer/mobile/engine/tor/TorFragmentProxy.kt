package com.uacspoofer.mobile.engine.tor

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny local SOCKS5 (no auth, CONNECT only) that Tor hands to the WebTunnel PT through
 * Socks5Proxy / TOR_PT_PROXY. It touches only the WebTunnel HTTPS hop: the first TLS
 * record the PT writes (its ClientHello) is split into small TCP writes, then bytes are
 * piped untouched. Inner Tor traffic is never fragmented.
 */
internal class TorFragmentProxy(
    private val chunkBytes: Int,
    private val delayMs: Int,
    private val helloOnly: Boolean,
    private val socketBufferBytes: Int = 0,
) {
    private val running = AtomicBoolean(false)
    private val sessions = AtomicInteger(0)
    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    fun start(): Int {
        stop()
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        server = socket
        running.set(true)
        Thread({
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread({ handle(client) }, "tor-frag-session").apply { isDaemon = true; start() }
            }
        }, "tor-frag-accept").apply { isDaemon = true; start() }
        AppLogRepository.info(
            LogSource.TOR,
            "WebTunnel fragment proxy on 127.0.0.1:${socket.localPort} chunk=$chunkBytes delay=${delayMs}ms",
        )
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    fun sessionCount(): Int = sessions.get()

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            if (socketBufferBytes > 0) {
                runCatching { client.sendBufferSize = socketBufferBytes; client.receiveBufferSize = socketBufferBytes }
            }
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val target = Socks5.readConnect(input, output) ?: return
            val remote = Socket()
            remote.tcpNoDelay = true
            if (socketBufferBytes > 0) {
                runCatching { remote.sendBufferSize = socketBufferBytes; remote.receiveBufferSize = socketBufferBytes }
            }
            remote.connect(InetSocketAddress(InetAddress.getByName(target.first), target.second), CONNECT_TIMEOUT_MS)
            upstream = remote
            output.write(Socks5.SUCCESS_REPLY)
            output.flush()
            client.soTimeout = 0
            sessions.incrementAndGet()
            val remoteOut = remote.getOutputStream()
            val first = TlsRecordSplitter.readFirstChunk(input)
            if (first != null) {
                if (!helloOnly || TlsRecordSplitter.isTlsHandshake(first)) {
                    TlsRecordSplitter.writeSplit(remoteOut, first, chunkBytes, delayMs)
                } else {
                    remoteOut.write(first)
                    remoteOut.flush()
                }
            }
            val back = Thread({ pipe(remote.getInputStream(), output, client, remote) }, "tor-frag-down")
            back.isDaemon = true
            back.start()
            pipe(input, remoteOut, client, remote)
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}

internal object Socks5 {
    val SUCCESS_REPLY = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
    private val FAIL_REPLY = byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)

    /** Reads greeting + CONNECT. Returns host/port, or null after replying with an error. */
    fun readConnect(input: DataInputStream, output: OutputStream): Pair<String, Int>? {
        if (input.readUnsignedByte() != 5) return null
        val methods = input.readUnsignedByte()
        val offered = ByteArray(methods).also { input.readFully(it) }
        if (offered.none { it.toInt() == 0 }) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(5, 0))
        output.flush()
        if (input.readUnsignedByte() != 5) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress.orEmpty()
            else -> null
        }
        val port = input.readUnsignedShort()
        if (command != 1 || host.isNullOrBlank()) {
            output.write(FAIL_REPLY)
            output.flush()
            return null
        }
        return host to port
    }
}

internal object TlsRecordSplitter {
    private const val MAX_PIECES = 64

    fun isTlsHandshake(data: ByteArray): Boolean =
        data.size >= 5 && data[0].toInt() == 0x16 && data[1].toInt() == 0x03

    fun readFirstChunk(input: InputStream): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val read = input.read(buffer)
        if (read <= 0) return null
        return buffer.copyOf(read)
    }

    /** Pieces of [chunk] bytes (at most [MAX_PIECES], remainder in the last write). */
    fun pieces(data: ByteArray, chunk: Int): List<ByteArray> {
        val size = chunk.coerceIn(1, 256)
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val last = out.size == MAX_PIECES - 1
            val end = if (last) data.size else minOf(data.size, offset + size)
            out += data.copyOfRange(offset, end)
            offset = end
        }
        return out
    }

    fun writeSplit(output: OutputStream, data: ByteArray, chunk: Int, delayMs: Int) {
        for (piece in pieces(data, chunk)) {
            output.write(piece)
            output.flush()
            if (delayMs > 0) Thread.sleep(delayMs.toLong())
        }
    }
}