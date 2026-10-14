package com.uacspoofer.mobile.engine.tor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorEngineImprovementsTest {
    private val bridgeA = WebTunnelBridgeParser.parseLine(
        "webtunnel 192.0.2.10:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000011111 url=https://a.example/x",
    )!!
    private val bridgeB = WebTunnelBridgeParser.parseLine(
        "webtunnel 192.0.2.11:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222 url=https://b.example/y",
    )!!

    @Test
    fun torrcHasWarmStartAndCircuitTuning() {
        val rc = TorRcWriter.render(TorEngineSettings.DEFAULT, File("/tmp/d"), File("/tmp/wt"), listOf(bridgeA))
        assertTrue(rc.contains("AvoidDiskWrites 0"))
        assertTrue(rc.contains("LearnCircuitBuildTimeout 1"))
        assertTrue(rc.contains("KeepalivePeriod 60"))
        assertTrue(rc.contains("ConfluxEnabled 1"))
        assertTrue(rc.contains("ReducedConnectionPadding 1"))
        assertTrue(rc.contains("DormantClientTimeout"))
        assertFalse(rc.contains("Socks5Proxy"))
        val noPad = TorRcWriter.render(
            TorEngineSettings.DEFAULT.copy(reducedPadding = false),
            File("/tmp/d"),
            File("/tmp/wt"),
            listOf(bridgeA),
        )
        assertFalse(noPad.contains("ReducedConnectionPadding"))
    }

    @Test
    fun fragmentProxyIsOnlyForTheWebTunnelHop() {
        val rc = TorRcWriter.render(
            TorEngineSettings.DEFAULT,
            File("/tmp/d"),
            File("/tmp/wt"),
            listOf(bridgeA),
            fragmentProxyPort = 41000,
        )
        assertTrue(rc.contains("Socks5Proxy 127.0.0.1:41000"))
        val classic = TorRcWriter.render(TorEngineSettings.DEFAULT, File("/tmp/d"), null, emptyList(), fragmentProxyPort = 41000)
        assertFalse(classic.contains("Socks5Proxy"))
    }

    @Test
    fun fastExitUsesNearbyCountriesNotStrict() {
        val lines = TorRcWriter.exitLines(TorEngineSettings.DEFAULT.copy(fastExit = true))
        assertEquals(listOf("ExitNodes {de},{nl},{ch},{at},{fr},{se},{pl},{gb}", "StrictNodes 0"), lines)
        val pinned = TorRcWriter.exitLines(TorEngineSettings(exitCountryCode = "se", exitStrict = true, fastExit = true).validated())
        assertEquals("ExitNodes {se}", pinned.first())
        val commands = TorExitCountry.controlCommands("", strict = false, fastExit = true)
        assertTrue(commands.first().contains("{de},{nl},{ch},{at},{fr},{se},{pl},{gb}"))
        assertTrue(TorExitCountry.exitNodesConfigured("250 ExitNodes={de},{nl},{ch},{at},{fr},{se},{pl},{gb}", "", fastExit = true))
        assertFalse(TorExitCountry.FAST_EXIT_CODES.any { it == "us" || it == "ca" })
        assertEquals(listOf("de", "nl"), TorExitCountry.FAST_EXIT_CODES.take(2))
    }

    @Test
    fun fragmentModeValidatesAndDerivesEnabled() {
        assertEquals(TorEngineSettings.FRAGMENT_AUTO, TorEngineSettings(fragmentMode = "weird").validated().fragmentMode)
        assertFalse(TorEngineSettings(fragmentMode = "off").fragmentEnabled)
        assertTrue(TorEngineSettings(fragmentMode = "on").fragmentEnabled)
    }

    @Test
    fun tlsSplitterKeepsBytesAndCapsPieces() {
        val hello = ByteArray(517) { it.toByte() }.also { it[0] = 0x16; it[1] = 0x03 }
        assertTrue(TlsRecordSplitter.isTlsHandshake(hello))
        val pieces = TlsRecordSplitter.pieces(hello, 5)
        assertEquals(64, pieces.size)
        assertEquals(5, pieces.first().size)
        val out = ByteArrayOutputStream()
        TlsRecordSplitter.writeSplit(out, hello, 5, 0)
        assertTrue(out.toByteArray().contentEquals(hello))
    }

    @Test
    fun socks5ParsesDomainConnect() {
        val request = byteArrayOf(5, 1, 0, 5, 1, 0, 3, 9) + "a.example".toByteArray() + byteArrayOf(1, 0xBB.toByte())
        val out = ByteArrayOutputStream()
        val target = Socks5.readConnect(DataInputStream(ByteArrayInputStream(request)), out)
        assertEquals("a.example" to 443, target)
    }

    @Test
    fun rankingPrefersSuccessAndCoolsDownFailures() {
        val now = 10_000_000L
        val scores = mapOf(
            TorBridgeRanking.key(bridgeA) to TorBridgeScore(fail = 3, consecutiveFail = 3, lastFail = now - 1_000),
            TorBridgeRanking.key(bridgeB) to TorBridgeScore(success = 2, latencyMs = 400),
        )
        val ordered = TorBridgeRanking.order(listOf(bridgeA, bridgeB), scores, now)
        assertEquals(bridgeB, ordered.first())
        assertEquals(bridgeA, ordered.last())
        val later = TorBridgeRanking.order(listOf(bridgeA), scores, now + TorBridgeScore.COOLDOWN_MS + 1)
        assertEquals(bridgeA, later.single())
    }

    @Test
    fun bridgeFingerprintAndGuardParsing() {
        assertEquals("AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000011111", bridgeA.fingerprint)
        val guards = "250+entry-guards=\n\$AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222~nick up\n" +
            "\$AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000011111~x never-connected\n.\n250 OK"
        assertEquals(listOf("AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222"), TorControlClient.parseUpGuardFingerprints(guards))
        val circuits = "250+circuit-status=\n3 BUILT \$AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222~b,\$1111111111111111111111111111111111111111~m PURPOSE=GENERAL\n.\n250 OK"
        assertEquals(listOf("AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222"), TorControlClient.parseFirstHops(circuits))
    }

    @Test
    fun stageTimeoutsAreShorterWhenWarm() {
        assertTrue(TorBootstrapWatch.handshakeMissed(16_000, reachedHandshake = false, coldStart = false, enabled = true))
        assertFalse(TorBootstrapWatch.handshakeMissed(16_000, reachedHandshake = false, coldStart = true, enabled = true))
        assertFalse(TorBootstrapWatch.handshakeMissed(99_000, reachedHandshake = false, coldStart = false, enabled = false))
        assertEquals(
            TorBootstrapWatch.DIRECTORY_STALL_WARM_MS,
            TorBootstrapWatch.stallBudgetMs(50, reachedHandshake = true, coldStart = false),
        )
        assertTrue(TorBootstrapWatch.isProxyRefusal("[warn] Managed proxy did not configure the specified outgoing proxy"))
    }

    @Test
    fun ptCrashLoopNeedsThreeTerminationsInTenSeconds() {
        val line = "[warn] {PT} Managed proxy \"/x/libwebtunnel.so\" having PID 1 terminated with status code 1"
        val counter = PtCrashCounter()
        assertFalse(counter.record(line, 0))
        assertFalse(counter.record("[notice] Bootstrapped 5%", 100))
        assertFalse(counter.record(line, 1_000))
        assertTrue(counter.record(line, 2_000))
        val slow = PtCrashCounter()
        assertFalse(slow.record(line, 0))
        assertFalse(slow.record(line, 11_000))
        assertFalse(slow.record(line, 22_000))
    }

    @Test
    fun hashedControlPasswordMatchesTorFormat() {
        val salt = ByteArray(8) { it.toByte() }
        val hash = TorControlAuth.hash("secret", salt)
        assertTrue(hash.startsWith("16:000102030405060760"))
        assertEquals(3 + 2 * (8 + 1 + 20), hash.length)
        val rc = TorRcWriter.render(TorEngineSettings.DEFAULT, File("/tmp/d"), null, emptyList(), hashedControlPassword = hash)
        assertTrue(rc.contains("HashedControlPassword $hash"))
    }

    @Test
    fun udpBlockShortensTimeoutAndRefreshParsesMoat() {
        assertTrue(TorTunRelayConfig.yaml(1280, 19050, blockUdp = true).contains("udp-read-write-timeout: 1000"))
        assertTrue(TorTunRelayConfig.yaml(1280, 19050, blockUdp = false).contains("udp-read-write-timeout: 15000"))
        val moat = """{"settings":[{"bridges":{"type":"webtunnel","bridge_strings":["webtunnel 192.0.2.3:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000033333 url=https:\/\/c.example\/z"]}}]}"""
        val lines = TorBridgeRefresh.extract(moat)
        assertEquals(1, lines.size)
        assertTrue(lines.first().contains("url=https://c.example/z"))
        assertTrue(TorBridgeRefresh.due(0L, TorBridgeRefresh.INTERVAL_MS))
    }
}