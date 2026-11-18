package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.settings.LanShareMode
import com.uacspoofer.mobile.settings.NetworkGuardSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class LanHttpProtocolTest {
    private fun input(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.ISO_8859_1))

    @Test fun oldSettingsAndUnknownModeKeepSocks5() {
        assertEquals(LanShareMode.SOCKS5, NetworkGuardSettings().lanShareMode)
        assertEquals(LanShareMode.SOCKS5, LanShareMode.fromId(null))
        assertEquals(LanShareMode.SOCKS5, LanShareMode.fromId("unknown"))
        assertEquals(LanShareMode.PLAYSTATION_5, LanShareMode.fromId("ps5"))
    }

    @Test fun connectLeavesCoalescedTlsBytesUntouched() {
        val stream = input("CONNECT api.example:443 HTTP/1.1\r\nHost: api.example:443\r\n\r\nTLS-DATA")
        val request = LanHttpProtocol.read(stream)
        assertTrue(request.tunnel)
        assertEquals("api.example", request.host)
        assertEquals(443, request.port)
        assertEquals("TLS-DATA", stream.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun absoluteTargetOverridesHostAndRemovesProxyOnlyHeaders() {
        val request = LanHttpProtocol.read(input("GET http://download.example:8080/a%20b?x=1 HTTP/1.1\r\n" +
            "Host: wrong.example\r\nRange: bytes=50-99\r\nProxy-Authorization: Basic secret\r\n" +
            "Connection: keep-alive, x-hop\r\nX-Hop: private\r\n\r\n"))
        val head = request.forwardedHead.toString(Charsets.ISO_8859_1)
        assertTrue(head.startsWith("GET /a%20b?x=1 HTTP/1.1\r\nHost: download.example:8080\r\n"))
        assertTrue(head.contains("range: bytes=50-99\r\n"))
        assertTrue(head.endsWith("Connection: close\r\n\r\n"))
        assertFalse(head.contains("secret")); assertFalse(head.contains("private"))
        assertEquals(8080, request.port)
    }

    @Test fun originFormAndIpv6AreSupported() {
        val request = LanHttpProtocol.read(input("HEAD / HTTP/1.0\r\nHost: example.test\r\n\r\n"))
        assertEquals("example.test", request.host); assertEquals(80, request.port)
        val ipv6 = LanHttpProtocol.read(input("CONNECT [2001:db8::1]:443 HTTP/1.0\r\n\r\n"))
        assertEquals("2001:db8::1", ipv6.host); assertEquals(443, ipv6.port)
    }

    @Test fun fixedBodyDoesNotForwardPipelinedRequests() {
        val stream = input("POST http://a.example/ HTTP/1.1\r\nHost: a.example\r\nContent-Length: 4\r\n\r\nDATANEXT")
        val request = LanHttpProtocol.read(stream)
        assertEquals("DATA", request.body(stream).readBytes().toString(Charsets.ISO_8859_1))
        assertEquals("NEXT", stream.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun chunkedBodyPreservesFramingButStopsAfterTrailers() {
        val body = "4;ext=value\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trail: ok\r\n\r\n"
        val stream = input("POST http://a.example/ HTTP/1.1\r\nHost: a.example\r\nTransfer-Encoding: chunked\r\n\r\n" + body + "NEXT")
        val request = LanHttpProtocol.read(stream)
        assertEquals(body, request.body(stream).readBytes().toString(Charsets.ISO_8859_1))
        assertEquals("NEXT", stream.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun malformedAndAmbiguousHeadersAreRejected() {
        val invalid = listOf(
            "CONNECT a.example HTTP/1.1\r\n\r\n",
            "CONNECT a.example:0 HTTP/1.1\r\n\r\n",
            "CONNECT a.example:65536 HTTP/1.1\r\n\r\n",
            "GET https://a.example/ HTTP/1.1\r\n\r\n",
            "GET http://user:pass@a.example/ HTTP/1.1\r\n\r\n",
            "GET http://a.example/#fragment HTTP/1.1\r\n\r\n",
            "GET /bad\tpath HTTP/1.1\r\nHost: a.example\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a.example\r\nHost: b.example\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a.example\r\nContent-Length: 4\r\nContent-Length: 4\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a.example\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a.example\r\nContent-Length: 99999999999999999999999\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a.example\r\nConnection: content-length\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a.example\r\n folded: header\r\n\r\n",
        )
        invalid.forEach { raw -> assertEquals(raw, 400, assertThrows(LanHttpError::class.java) { LanHttpProtocol.read(input(raw)) }.status) }
    }

    @Test fun headerSizeAndUnsupportedVersionsAreBounded() {
        assertEquals(431, assertThrows(LanHttpError::class.java) {
            LanHttpProtocol.read(input("GET / HTTP/1.1\r\nHost: a.example\r\nX-Large: " + "a".repeat(33_000) + "\r\n\r\n"))
        }.status)
        assertEquals(505, assertThrows(LanHttpError::class.java) {
            LanHttpProtocol.read(input("GET / HTTP/2.0\r\nHost: a.example\r\n\r\n"))
        }.status)
    }

    @Test fun invalidChunksAndForbiddenTrailersFailClosed() {
        for (body in listOf("g\r\n", "ffffffffffffffff\r\n", "1\r\naXX", "0\r\nContent-Length: 5\r\n\r\n")) {
            val stream = input("POST / HTTP/1.1\r\nHost: a.example\r\nTransfer-Encoding: chunked\r\n\r\n$body")
            val request = LanHttpProtocol.read(stream)
            assertThrows(IOException::class.java) { request.body(stream).readBytes() }
        }
    }
}
