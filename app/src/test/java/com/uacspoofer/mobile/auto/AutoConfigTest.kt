package com.uacspoofer.mobile.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConfigTest {
    private val ws = "vless://e6df2095-a73c-4043-8693-8d5a03686870@188.114.98.0:443?encryption=none&security=tls" +
        "&type=ws&host=lucasjow.us.ci&path=%2F%3FSAFE&sni=lucasjow.us.ci&alpn=http%2F1.1"
    private val fmA = "$ws&fp=unsafe&cs=TLS_AES_256_GCM_SHA384&fm=%7B%22tcp%22%3A%5B%5D%7D#CA%20%7C%20A"
    private val echB = "$ws&fp=chrome&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1#CA%20%7C%20B"
    private val v6C = "vless://e6df2095-a73c-4043-8693-8d5a03686870@[2a06:98c1:3101::6812:202f]:443?encryption=none" +
        "&security=tls&type=ws&host=lucasjow.us.ci&path=%2F%3FSAFE&sni=lucasjow.us.ci&fp=chrome#CA%20%7C%20C"
    private val trojanFm = "trojan://humanity@188.114.98.0:443?security=tls&type=ws&host=www.pleadcourt.org" +
        "&path=%2Fassignment&sni=www.pleadcourt.org&fp=unsafe&cs=X&fm=%7B%7D#ES"
    private val trojanEch = "trojan://humanity@188.114.98.0:443?security=tls&type=ws&host=www.pleadcourt.org" +
        "&path=%2Fassignment&sni=www.pleadcourt.org&fp=chrome&echcl=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1#ES2"
    private val list = listOf(fmA, echB, v6C, trojanFm, trojanEch, "# comment", "ss://ignored").joinToString("\n")

    @Test
    fun parsesAndClassifiesVariants() {
        val parsed = PatternihaList.parse(list)
        assertEquals(5, parsed.size)
        assertEquals(AutoVariant.FM, parsed[0].variant)
        assertEquals(AutoVariant.ECH, parsed[1].variant)
        assertEquals(AutoVariant.IPV6, parsed[2].variant)
        assertEquals("2a06:98c1:3101::6812:202f", parsed[2].address)
        assertEquals(AutoVariant.ECH, parsed[4].variant)
        assertEquals(parsed[0].nodeKey, parsed[1].nodeKey)
    }

    @Test
    fun sniNetworksTryEchFirstAndSkipIpv6WithoutIpv6() {
        val ordered = PatternihaList.order(PatternihaList.parse(list), DpiType.SNI, hasIpv6 = false, limit = 10)
        assertEquals(listOf(AutoVariant.ECH, AutoVariant.FM, AutoVariant.ECH, AutoVariant.FM), ordered.map { it.variant })
    }

    @Test
    fun ipNetworksTryFmFirstWithCleanIpCopiesLeading() {
        val parsed = PatternihaList.parse(list)
        val clean = parsed[0].copy(uri = PatternihaList.withAddress(parsed[0].uri, "104.26.13.100"), address = "104.26.13.100", cleanIp = true)
        val ordered = PatternihaList.order(parsed + clean, DpiType.IP, hasIpv6 = true, limit = 10)
        assertTrue(ordered.first().cleanIp)
        // FM leads but ECH is interleaved so a dead FM set can never starve the capped test.
        assertEquals(AutoVariant.ECH, ordered[1].variant)
        assertEquals(AutoVariant.IPV6, ordered.last().variant)
        assertTrue(ordered.take(6).count { it.variant == AutoVariant.ECH } >= 2)
    }

    @Test
    fun unknownInterleavesEchAndFm() {
        val ordered = PatternihaList.order(PatternihaList.parse(list), DpiType.UNKNOWN, hasIpv6 = false, limit = 4)
        assertEquals(setOf(AutoVariant.ECH, AutoVariant.FM), ordered.take(2).map { it.variant }.toSet())
    }

    @Test
    fun rewritesAddressAndEchKey() {
        assertEquals(
            "trojan://humanity@1.2.3.4:443?security=tls#x",
            PatternihaList.withAddress("trojan://humanity@188.114.98.0:443?security=tls#x", "1.2.3.4"),
        )
        assertEquals(
            "vless://u@[2a06::1]:443?a=b",
            PatternihaList.withAddress("vless://u@9.9.9.9:443?a=b", "2a06::1"),
        )
        val keyed = PatternihaList.withEchKey(echB, "AEX+/w==")
        assertTrue(keyed.contains("&ech=AEX%2B%2Fw%3D%3D"))
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", PatternihaList.echValue(echB))
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", PatternihaList.echValue(trojanEch))
    }

    @Test
    fun patternihaMirrorsFallBackButOtherUrlsDoNot() {
        assertEquals(PatternihaList.MIRRORS, PatternihaList.mirrorsFor(PatternihaList.RAW_URL))
        assertEquals(PatternihaList.JSDELIVR_URL, PatternihaList.mirrorsFor(PatternihaList.JSDELIVR_URL).first())
        assertEquals(listOf("https://example.com/x.txt"), PatternihaList.mirrorsFor("https://example.com/x.txt"))
    }

    @Test
    fun rankingCacheKeepsHealthyTopTenAndExpires() {
        val now = 10L * AutoRanking.STALE_MS
        val old = listOf(
            AutoRankedEntry("a", 300, now - 1000),
            AutoRankedEntry("b", 100, now - AutoRanking.STALE_MS - 1),
            AutoRankedEntry("c", 200, now - 1000, failures = AutoRanking.MAX_FAILURES),
        )
        assertEquals(listOf("a"), AutoRanking.healthy(old, now).map { it.uri })
        val fresh = (1..12).map { AutoRankedEntry("f$it", it * 10L, now) }
        val merged = AutoRanking.merge(old, fresh, now)
        assertEquals(AutoRanking.KEEP, merged.size)
        assertEquals("f1", merged.first().uri)
        assertFalse(AutoRanking.needsBootstrap(merged, now))
        assertTrue(AutoRanking.needsBootstrap(merged, now + AutoRanking.STALE_MS + 1))
        val failed = AutoRanking.markFailure(AutoRanking.markFailure(merged, "f1"), "f1")
        assertEquals("f2", AutoRanking.healthy(failed, now).first().uri)
        assertEquals(0, AutoRanking.markSuccess(failed, "f1").first().failures)
        assertEquals(merged, AutoRanking.decode(AutoRanking.encode(merged)))
    }

    @Test
    fun dpiClassificationMatchesDesktopAndCarrierVote() {
        assertEquals(DpiType.SNI, DpiDecision.classify(6, 0, "irancell").dpi)
        assertEquals(DpiType.IP, DpiDecision.classify(6, 5, "mci").dpi)
        assertEquals("medium", DpiDecision.classify(6, 3, "").confidence)
        assertEquals(DpiType.SNI, DpiDecision.classify(0, 0, "mci").dpi)
        assertEquals(DpiType.IP, DpiDecision.classify(1, 0, "rightel").dpi)
        assertEquals(DpiType.UNKNOWN, DpiDecision.classify(0, 0, "wifi").dpi)
    }

    @Test
    fun echDnsRoundTrip() {
        val ech = byteArrayOf(0, 6, 0xfe.toByte(), 0x0d, 0, 2, 1, 2)
        val query = EchDns.query("cloudflare-ech.com", 0x1234)
        // response = query header with QR bit + 1 answer, question copied, OPT dropped
        val questionEnd = 12 + "cloudflare-ech.com".split('.').sumOf { it.length + 1 } + 1 + 4
        val out = java.io.ByteArrayOutputStream()
        out.write(query, 0, 12)
        val header = out.toByteArray().also { it[2] = 0x81.toByte(); it[7] = 1; it[11] = 0 }
        val rdata = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 1, 0)) // priority 1, target "."
            write(byteArrayOf(0, 1, 0, 3, 2, 'h'.code.toByte(), '2'.code.toByte())) // alpn
            write(byteArrayOf(0, 5, 0, ech.size.toByte())); write(ech)
        }.toByteArray()
        val msg = java.io.ByteArrayOutputStream().apply {
            write(header)
            write(query, 12, questionEnd - 12)
            write(byteArrayOf(0xc0.toByte(), 12, 0, 65, 0, 1, 0, 0, 1, 0, 0, rdata.size.toByte()))
            write(rdata)
        }.toByteArray()
        val key = EchDns.echFromResponse(msg, 0x1234)
        assertEquals("AAb+DQACAQI=", key)
        assertEquals("", EchDns.echFromResponse(msg, 0x9999))
        assertEquals("cloudflare-ech.com" to "udp://1.1.1.1", EchDns.parseEchValue("cloudflare-ech.com+udp://1.1.1.1"))
        assertEquals("1.1.1.1", EchDns.hintedServer("udp://1.1.1.1"))
        val list = com.uacspoofer.mobile.profiles.Base64Codec.encode(byteArrayOf(0, 12) + ByteArray(12) { it.toByte() })
        assertTrue(EchDns.isBase64List(list))
        assertFalse(EchDns.isBase64List("cloudflare-ech.com+udp://1.1.1.1"))
    }

    @Test
    fun rankingDecodesOldLinesAndRanksByDelayPlusSpeed() {
        val old = AutoRanking.decode("300\t1000\t0\tvless://a@1.1.1.1:443?x=1#a")
        assertEquals(1, old.size)
        assertEquals(0L, old[0].kbps)
        val fast = AutoRankedEntry("vless://f@1.1.1.1:443#f", 400, 1000, kbps = 20_000)
        val slow = AutoRankedEntry("vless://s@1.1.1.1:443#s", 300, 1000, kbps = 500)
        val round = AutoRanking.decode(AutoRanking.encode(listOf(fast, slow)))
        assertEquals(listOf(fast, slow), round)
        assertEquals(listOf(fast, slow), AutoRanking.healthy(round, 2000))
        val merged = AutoRanking.merge(round, listOf(slow.copy(kbps = 0, delayMs = 250)), 2000)
        assertEquals(500L, merged.first { it.uri == slow.uri }.kbps)
    }
}