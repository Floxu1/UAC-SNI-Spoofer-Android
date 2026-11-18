package com.uacspoofer.mobile.engine.pow

import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PowStartupDownloadProbeTest {
    @Test fun measuresBodyThroughSocksWithABoundedDownload() = runBlocking {
        val listener = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val request = worker.submit<Int> {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    fun readByte(): Int = input.read().also { check(it >= 0) }
                    fun skip(count: Int) { repeat(count) { readByte() } }
                    check(readByte() == 5)
                    skip(readByte())
                    output.write(byteArrayOf(5, 0))
                    output.flush()
                    check(readByte() == 5)
                    check(readByte() == 1)
                    readByte()
                    when (readByte()) {
                        1 -> skip(4)
                        3 -> skip(readByte())
                        4 -> skip(16)
                        else -> error("bad SOCKS address")
                    }
                    skip(2)
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                    output.flush()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) tail = (tail + readByte().toChar()).takeLast(4)
                    val count = PowStartupDownloadProbe.MAX_BYTES
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: $count\r\nConnection: close\r\n\r\n".toByteArray())
                    val chunk = ByteArray(16 * 1024) { 1 }
                    repeat(count / chunk.size) {
                        output.write(chunk)
                        output.flush()
                        Thread.sleep(4)
                    }
                    count
                }
            }
            val diagnostics = mutableListOf<String>()
            val result = PowStartupDownloadProbe.measure(listener.localPort, URL("http://download.invalid/test")) { diagnostics += it }
            assertNotNull(diagnostics.joinToString(), result)
            assertEquals(PowStartupDownloadProbe.MAX_BYTES, request.get(8, TimeUnit.SECONDS))
            assertTrue(result!!.bitsPerSecond > 0)
        } finally {
            listener.close()
            worker.shutdownNow()
            worker.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
    @Test fun chunkedBodyHandlesExtensionsAndSeveralChunks() {
        val encoded = "3;tag=x\r\nabc\r\n2\r\nde\r\n0\r\n\r\n".byteInputStream()
        assertEquals("abcde", PowStartupDownloadProbe.ChunkedBody(encoded).readBytes().toString(Charsets.US_ASCII))
    }

    @Test(expected = IllegalStateException::class)
    fun truncatedChunkIsRejected() {
        PowStartupDownloadProbe.ChunkedBody("8\r\nabc".byteInputStream()).readBytes()
    }

}
