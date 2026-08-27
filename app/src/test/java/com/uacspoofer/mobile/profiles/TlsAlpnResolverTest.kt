package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsAlpnResolverTest {
    @Test
    fun parseValuesSupportsCommaAndSlash() {
        assertEquals(listOf("h2", "http/1.1"), TlsAlpnResolver.parseValues("h2,http/1.1"))
        assertEquals(listOf("h2", "http/1.1"), TlsAlpnResolver.parseValues("h2/http/1.1"))
        assertEquals(listOf("http/1.1"), TlsAlpnResolver.parseValues("http1.1"))
    }

    @Test
    fun wsPrefersHttp11BeforeH2() {
        assertEquals(
            listOf("http/1.1", "h2"),
            TlsAlpnResolver.resolveForTransport("ws", "h2,http/1.1"),
        )
    }

    @Test
    fun grpcPrefersH2() {
        assertEquals(
            listOf("h2", "http/1.1"),
            TlsAlpnResolver.resolveForTransport("grpc", "http/1.1,h2"),
        )
    }

    @Test
    fun xhttpKeepsRequestedAlpnAndDefaultsToH2() {
        assertEquals(listOf("h2"), TlsAlpnResolver.resolveForTransport("xhttp", ""))
        assertEquals(
            listOf("h2", "http/1.1"),
            TlsAlpnResolver.resolveForTransport("xhttp", "h2,http/1.1"),
        )
    }

    @Test
    fun preserveEmptyAlpnOmitsTlsAlpnForDirectCompat() {
        assertTrue(
            TlsAlpnResolver.resolveForXray("ws", "", preserveEmptyAlpn = true).isEmpty(),
        )
        assertEquals(
            listOf("h2", "http/1.1"),
            TlsAlpnResolver.resolveForXray("xhttp", "h2,http/1.1", preserveEmptyAlpn = true),
        )
    }
}
