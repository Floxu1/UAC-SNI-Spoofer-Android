package com.uacspoofer.mobile.engine.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectStartupReportTest {
    @Test
    fun reportKeepsTheLatestStageForEachIpAndPrioritizesKnownCandidates() {
        val prefix = "Direct candidate 192.0.2.1:443 known"
        val latest = "$prefix stage=masque_connect_ip result=failed elapsed_ms=800 http_status=400 reason=request_rejected"
        val stage = "Direct startup stage=carrier_search known=1 sampled=20 range=0 shared_h2_hints=1"
        val candidates = (1..20).map {
            "Direct candidate 198.51.100.$it:443 range stage=tls_handshake result=failed"
        }
        val snapshot = (listOf(stage, "$prefix stage=tcp_connect result=interrupted", latest) + candidates)
            .joinToString("\n")

        val report = directStartupReport(snapshot)
        assertEquals(13, report.size)
        assertEquals(stage, report.first())
        assertEquals(latest, report[1])
        assertTrue(report.contains(candidates.last()))
        assertFalse(report.any { it.startsWith("$prefix stage=tcp_connect") })
    }

    @Test
    fun unrelatedNativeMessagesAreExcludedFromTheExport() {
        val stage = "Direct startup stage=masque_identity"
        assertEquals(listOf(stage), directStartupReport("$stage\nunstructured native detail"))
    }
}
