package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.ui.ConnectProgressModel
import com.uacspoofer.mobile.ui.MiniStepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectIpScanTest {
    @Test fun `retry can recover selected IP after the scan header rolls out of the log`() {
        val log = """
            Direct IP 192.0.2.1:443 range selected 67
            Direct IP 192.0.2.2:443 range https_failed
            Direct WireGuard handshake and data validated via H2 192.0.2.1:443
        """.trimIndent()
        assertEquals("192.0.2.1", DirectIpScan.selectedCarrierIp(log))
        assertNull(DirectIpScan.selectedCarrierIp("Direct IP 192.0.2.2:443 known https_failed"))
    }

    @Test fun `wide scan reports the measured ping for the selected route`() {
        val scan = checkNotNull(DirectIpScan.fromNativeLog(
            """
            Direct IP scan 3810
            Direct IP sweep 3810
            Direct IP 192.0.2.1:443 range wireguard
            Direct IP 192.0.2.1:443 range selected 67
            """.trimIndent(),
        ))
        assertEquals(3810, scan.sweepChecked)
        assertEquals(67L, scan.selectedPingMs)
    }

    @Test fun `parallel checks retain the first order and latest result for each IP`() {
        val scan = checkNotNull(DirectIpScan.fromNativeLog(
            """
            Other engine message
            Direct IP scan 24
            Direct IP 192.0.2.1:443 known https
            Direct IP 192.0.3.1:443 range https
            Direct IP 192.0.3.1:443 range wireguard
            Direct IP 192.0.2.1:443 known https_failed
            Direct IP 192.0.3.1:443 range selected
            """.trimIndent(),
        ))
        assertEquals(24, scan.total)
        assertEquals(listOf("192.0.2.1:443", "192.0.3.1:443"), scan.checks.map { it.address })
        assertEquals(listOf(DirectIpStage.HTTPS_FAILED, DirectIpStage.SELECTED), scan.checks.map { it.stage })
        assertTrue(scan.checks.last().fromRange)
        assertTrue(scan.checks.all { it.finished })
        assertNull(DirectIpScan.fromNativeLog("Direct IP 192.0.3.1:443 range selected"))
    }

    @Test fun `IP stage appears only for Cloudflare and stays highlighted when it fails`() {
        val direct = ConnectProgressModel.directSteps(DirectPhase.STARTING, DirectPhase.STARTING, scanIps = false)
        assertEquals(listOf("config", "core", "check"), direct.map { it.key })
        val cloudflare = ConnectProgressModel.directSteps(DirectPhase.FAILED, DirectPhase.SCANNING, scanIps = true)
        assertEquals("scan", cloudflare.single { it.state == MiniStepState.FAILED }.key)
    }
}
