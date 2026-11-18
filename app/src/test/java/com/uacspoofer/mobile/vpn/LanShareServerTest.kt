package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.settings.LanShareMode
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LanShareServerTest {
    private data class Target(val host: String, val port: Int)
    private class FakeSocks(
        private val replyCode: Int = 0,
        private val ipv6Reply: Boolean = false,
        private val action: (InputStream, OutputStream) -> Unit,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        val targets = LinkedBlockingQueue<Target>()
        val errors = LinkedBlockingQueue<Throwable>()
        val port get() = listener.localPort
        init {
            Thread({
                while (!listener.isClosed) {
                    val client = runCatching { listener.accept() }.getOrNull() ?: break
                    clients.add(client)
                    Thread({
                        try {
                            client.soTimeout = 3_000
                            val input = BufferedInputStream(client.getInputStream())
                            val output = client.getOutputStream()
                            assertArrayEquals(byteArrayOf(5, 1, 0), exact(input, 3))
                            output.write(byteArrayOf(5, 0)); output.flush()
                            val head = exact(input, 4)
                            assertArrayEquals(byteArrayOf(5, 1, 0), head.copyOfRange(0, 3))
                            val host = when (head[3].toInt()) {
                                3 -> exact(input, input.read()).toString(Charsets.US_ASCII)
                                1 -> InetAddress.getByAddress(exact(input, 4)).hostAddress!!
                                4 -> InetAddress.getByAddress(exact(input, 16)).hostAddress!!
                                else -> error("unexpected address type")
                            }
                            val portBytes = exact(input, 2)
                            targets.add(Target(host, ((portBytes[0].toInt() and 255) shl 8) or (portBytes[1].toInt() and 255)))
                            output.write(byteArrayOf(5, replyCode.toByte(), 0, if (ipv6Reply) 4 else 1) + ByteArray(if (ipv6Reply) 18 else 6))
                            output.flush()
                            if (replyCode == 0) action(input, output)
                        } catch (error: Throwable) {
                            if (!listener.isClosed) errors.add(error)
                        } finally { clients.remove(client); runCatching { client.close() } }
                    }, "fake-socks-client").apply { isDaemon = true }.start()
                }
            }, "fake-socks").apply { isDaemon = true }.start()
        }
        fun healthy() { errors.poll()?.let { throw AssertionError("fake upstream failed", it) } }
        override fun close() { listener.close(); clients.forEach { runCatching { it.close() } } }
    }

    private fun server(upstream: FakeSocks, mode: LanShareMode = LanShareMode.PLAYSTATION_5, port: Int = 0) =
        LanShareServer(port, "127.0.0.1", upstream.port, mode).start()
    private fun client(port: Int) = Socket("127.0.0.1", port).apply { soTimeout = 3_000 }
    private fun write(socket: Socket, text: String) { socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1)); socket.getOutputStream().flush() }

    @Test fun connectRelaysCoalescedBinaryDataAndConsumesFullSocksReply() {
        val data = ByteArray(8192) { (it * 17).toByte() }
        FakeSocks(ipv6Reply = true) { input, output -> output.write(input.readBytes()); output.flush() }.use { upstream ->
            server(upstream).use { proxy ->
                client(proxy.port).use { socket ->
                    socket.getOutputStream().write("CONNECT tls.example.invalid:443 HTTP/1.1\r\nHost: tls.example.invalid:443\r\n\r\n".toByteArray() + data)
                    socket.shutdownOutput()
                    assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 200 "))
                    assertArrayEquals(data, socket.getInputStream().readBytes())
                }
                assertEquals(Target("tls.example.invalid", 443), upstream.targets.poll(2, TimeUnit.SECONDS))
                upstream.healthy()
            }
        }
    }

    @Test fun plainDownloadRewritesTargetAndStripsCredentialsAndPipeline() {
        val payload = ByteArray(1024 * 1024) { (it * 31).toByte() }
        val received = LinkedBlockingQueue<String>()
        FakeSocks { input, output ->
            received.add(head(input))
            assertEquals(0, input.readBytes().size)
            output.write("HTTP/1.1 206 Partial Content\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray() + payload)
            output.flush()
        }.use { upstream ->
            server(upstream).use { proxy ->
                client(proxy.port).use { socket ->
                    write(socket, "GET http://download.example.invalid/file?x=1 HTTP/1.1\r\nHost: ignored.example\r\nRange: bytes=0-1048575\r\nProxy-Authorization: Basic secret\r\nProxy-Connection: keep-alive\r\n\r\nGET http://second.example/ HTTP/1.1\r\nHost: second.example\r\n\r\n")
                    assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 206"))
                    assertArrayEquals(payload, socket.getInputStream().readBytes())
                }
                val request = received.poll(2, TimeUnit.SECONDS)!!
                assertTrue(request.startsWith("GET /file?x=1 HTTP/1.1\r\nHost: download.example.invalid:80\r\n"))
                assertTrue(request.contains("range: bytes=0-1048575"))
                assertFalse(request.contains("secret")); assertFalse(request.contains("Proxy-Connection"))
                assertEquals(Target("download.example.invalid", 80), upstream.targets.poll(2, TimeUnit.SECONDS))
                upstream.healthy()
            }
        }
    }

    @Test fun chunkedUploadKeepsFramingAndDoesNotLeakNextRequest() {
        val body = "4\r\nDATA\r\n0\r\nX-Checksum: yes\r\n\r\n"
        val received = LinkedBlockingQueue<String>()
        FakeSocks { input, output ->
            head(input); received.add(input.readBytes().toString(Charsets.ISO_8859_1))
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray()); output.flush()
        }.use { upstream ->
            server(upstream).use { proxy ->
                client(proxy.port).use { socket ->
                    write(socket, "POST http://a.example/upload HTTP/1.1\r\nHost: a.example\r\nTransfer-Encoding: chunked\r\n\r\n" + body + "DO NOT FORWARD")
                    assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 200"))
                    assertEquals("OK", socket.getInputStream().readBytes().toString(Charsets.US_ASCII))
                }
                assertEquals(body, received.poll(2, TimeUnit.SECONDS)); upstream.healthy()
            }
        }
    }

    @Test fun failedUpstreamNeverReportsSuccessfulConnect() {
        FakeSocks(replyCode = 5) { _, _ -> error("must not tunnel") }.use { upstream ->
            server(upstream).use { proxy ->
                client(proxy.port).use { socket ->
                    write(socket, "CONNECT blocked.example:443 HTTP/1.1\r\n\r\n")
                    assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 502"))
                    assertEquals(0, socket.getInputStream().readBytes().size)
                }
                upstream.healthy()
            }
        }
    }

    @Test fun existingSocks5ModeStillRelaysBinaryAndRejectsUnsupportedAuth() {
        val payload = byteArrayOf(0, 1, 13, 10, 127, -1)
        FakeSocks { input, output -> output.write(input.readBytes()); output.flush() }.use { upstream ->
            server(upstream, LanShareMode.SOCKS5).use { proxy ->
                client(proxy.port).use { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    assertArrayEquals(byteArrayOf(5, 0), exact(socket.getInputStream(), 2))
                    socket.getOutputStream().write(LanShareSocks.request("echo.example", 1234))
                    assertArrayEquals(byteArrayOf(5, 0, 0, 1) + ByteArray(6), exact(socket.getInputStream(), 10))
                    socket.getOutputStream().write(payload); socket.shutdownOutput()
                    assertArrayEquals(payload, socket.getInputStream().readBytes())
                }
                client(proxy.port).use { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1, 2))
                    assertArrayEquals(byteArrayOf(5, -1), exact(socket.getInputStream(), 2))
                    assertEquals(-1, socket.getInputStream().read())
                }
                upstream.healthy()
            }
        }
    }

    @Test fun modeSwitchClosesOldClientsAndRebindsSamePort() {
        FakeSocks { input, output -> output.write(input.readBytes()); output.flush() }.use { upstream ->
            val old = server(upstream, LanShareMode.SOCKS5)
            val port = old.port
            val socket = client(port)
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertArrayEquals(byteArrayOf(5, 0), exact(socket.getInputStream(), 2))
            old.close()
            assertEquals(-1, socket.getInputStream().read())
            socket.close()
            server(upstream, port = port).use { replacement ->
                client(port).use { next ->
                    write(next, "CONNECT echo.example:443 HTTP/1.1\r\n\r\nhello")
                    next.shutdownOutput()
                    assertTrue(head(next.getInputStream()).startsWith("HTTP/1.1 200"))
                    assertEquals("hello", next.getInputStream().readBytes().toString(Charsets.US_ASCII))
                }
                assertTrue(replacement.isRunning); upstream.healthy()
            }
        }
    }

    @Test fun streamAdmissionIsBoundedAndStopClosesPendingHandshakes() {
        val proxy = LanShareServer(0, "127.0.0.1", 9, LanShareMode.SOCKS5).start()
        val sockets = mutableListOf<Socket>()
        try {
            repeat(LanSharePolicy.MAX_STREAMS) { sockets.add(client(proxy.port)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (proxy.activeConnections < LanSharePolicy.MAX_STREAMS && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(LanSharePolicy.MAX_STREAMS, proxy.activeConnections)
            client(proxy.port).use { assertEquals(-1, it.getInputStream().read()) }
            proxy.close()
            sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
        } finally { proxy.close(); sockets.forEach { it.close() } }
    }

    companion object {
        private fun exact(input: InputStream, count: Int): ByteArray {
            require(count >= 0)
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read > 0) { "unexpected EOF" }
                offset += read
            }
            return bytes
        }
        private fun head(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            var last = 0
            while (bytes.size() < 65536) {
                val next = input.read(); check(next >= 0) { "header EOF" }; bytes.write(next)
                last = (last shl 8) or next
                if (last == 0x0d0a0d0a) return bytes.toString(Charsets.ISO_8859_1.name())
            }
            error("header too large")
        }
    }
}
