package com.uacspoofer.mobile.engine.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorFastRelaysTest {
    private fun id(byte: Int) = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(20) { byte.toByte() })

    @Test
    fun picksFastStableByBandwidth() {
        val body = buildString {
            appendLine("250+ns/all=")
            listOf(1 to 500L, 2 to 9000L, 3 to 7000L).forEach { (b, bw) ->
                appendLine("r n$b ${id(b)} 2026-10-13 00:00:00 1.2.3.$b 9001 0")
                appendLine("s Fast Running Stable Valid" + if (b == 3) " BadExit" else "")
                appendLine("w Bandwidth=$bw")
            }
            appendLine("r slow ${id(4)} 2026-10-13 00:00:00 1.2.3.4 9001 0")
            appendLine("s Running Valid")
            appendLine("w Bandwidth=99999")
            appendLine(".")
        }
        val picked = TorFastRelays.pickMiddles(TorFastRelays.parse(body))
        assertEquals(listOf("02".repeat(20), "01".repeat(20)), picked)
        assertEquals("MiddleNodes=", TorFastRelays.middleNodesConf(picked))
        assertTrue(TorFastRelays.middleNodesConf(List(12) { "AB".repeat(20) }).startsWith("MiddleNodes=\$ABAB"))
    }

    @Test
    fun multiCircuitIsolatesSocks() {
        val rc = TorRcWriter.render(TorEngineSettings(multiCircuit = true), java.io.File("data"), null, emptyList())
        assertTrue(rc.lines().any { it.startsWith("SocksPort") && it.endsWith("IsolateDestAddr") })
    }
}