package com.uacspoofer.mobile.profiles

import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayConfigBuilder
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real lines from the Patterniha list (variants A/B/C, Trojan, xhttp) plus REALITY. */
class XrayPortLinksTest {
    private val fm = "%7B%22tcp%22%3A%20%5B%7B%22type%22%3A%20%22fragment%22%2C%20%22settings%22%3A%20%7B%22packets%22%3A%20%22tlshello%22%2C%20%22lengths%22%3A%20%5B%220%22%2C%20%22104%22%2C%20%221%22%5D%2C%20%22delays%22%3A%20%5B%220%22%5D%2C%20%22maxSplit%22%3A%20%220%22%7D%7D%2C%7B%22type%22%3A%20%22fragment%22%2C%20%22settings%22%3A%20%7B%22packets%22%3A%20%221-1%22%2C%20%22lengths%22%3A%20%5B%22114%22%2C%20%221%22%5D%2C%20%22delays%22%3A%20%5B%221%22%5D%2C%20%22maxSplit%22%3A%20%2211%22%7D%7D%5D%7D"
    private val cs = "TLS_AES_256_GCM_SHA384%3ATLS_CHACHA20_POLY1305_SHA256%3ATLS_AES_128_GCM_SHA256%3ATLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256%3ATLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256%3ATLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256%3ATLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256%3ATLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA%3ATLS_ECDHE_RSA_WITH_AES_256_CBC_SHA%3ATLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256%3ATLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"
    private val ws = "vless://e6df2095-a73c-4043-8693-8d5a03686870@188.114.98.0:443?encryption=none&security=tls" +
        "&type=ws&host=lucasjow.us.ci&path=%2F%3FSAFEWEBS_SAFEWEBS_SAFEWEBS_SAFEWEBS&sni=lucasjow.us.ci&alpn=http%2F1.1"
    private val variantA = "$ws&fp=unsafe&cs=$cs&fm=$fm#CA%20%7C%205DD153%20%7C%20d9aca0"
    private val variantB = "$ws&fp=chrome&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1#CA%20%7C%2030cbae"
    private val variantC = "vless://e6df2095-a73c-4043-8693-8d5a03686870@[2a06:98c1:3101::6812:202f]:443?encryption=none" +
        "&security=tls&type=ws&host=lucasjow.us.ci&path=%2F%3FSAFEWEBS_SAFEWEBS_SAFEWEBS_SAFEWEBS&sni=lucasjow.us.ci" +
        "&alpn=http%2F1.1&fp=chrome#CA%20%7C%206a0638"
    private val trojan = "trojan://humanity@188.114.98.0:443?security=tls&type=ws&host=www.pleadcourt.org" +
        "&path=%2Fassignment&sni=www.pleadcourt.org&alpn=http%2F1.1&fp=unsafe&cs=$cs&fm=$fm#ES%20%7C%20E8E89B"
    private val xhttp = "vless://86c0996e-817e-409f-a732-ff9ef94917bd@188.114.98.0:443?encryption=none&security=tls" +
        "&type=xhttp&host=s3.bamans.uk&path=%2F3a6b3b038ea34622bd1e13ec5798ab03&mode=stream-up&sni=s3.bamans.uk" +
        "&alpn=h2&fp=unsafe&cs=$cs&fm=$fm#XH"
    private val junk = "vless://495c7195-85b8-498a-bf20-2ea9ce9175b5@188.114.98.0:443?security=tls&type=ws" +
        "&Host=snip.edgeoneai.cc.cd&path=%2F&sni=snip.edgeoneai.cc.cd&alpn=http%2F1.1&fp=chrome" +
        "&echcl=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1&Telegram=@Farah_VPN&packetEncoding=xudp&headerType=none" +
        "&fragment=1,40-60,30-50#Junk"
    private val reality = "vless://11111111-1111-1111-1111-111111111111@5.6.7.8:443?encryption=none&security=reality" +
        "&type=tcp&sni=www.microsoft.com&fp=firefox&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179e30d4fc2" +
        "&spx=%2F&flow=xtls-rprx-vision#R"

    private val settings = AdvancedSettingsData.DEFAULT

    private fun config(raw: String, name: String? = null): String {
        val profile = ProfileUriParser.parse(raw)
        val direct = DirectCompatProfileParser.parse(profile)!!
        val json = MciXrayConfigBuilder.build(
            edge = MciEdge(direct.address, direct.port, "direct", 1),
            settings = settings,
            profile = profile,
            nativeTun = false,
            runtimeOptions = MciXrayRuntimeOptions(
                identityOverride = direct.identity,
                finalmaskEnabled = false,
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            ),
        )
        if (name != null) {
            val dir = File("build/xray-check").apply { mkdirs() }
            File(dir, "$name.json").writeText(json)
        }
        return json
    }

    /** The first outbound is the proxy; every check below reads that slice only. */
    private fun proxy(config: String): String =
        config.substringAfter("\"outbounds\":[").substringBefore("{\"tag\":\"probe-proxy\"")

    @Test
    fun variantAKeepsUnsafeFingerprintCipherSuitesAndFinalmask() {
        val profile = ProfileUriParser.parse(variantA)
        assertEquals("unsafe", profile.fingerprint)
        assertTrue(profile.cipherSuites.startsWith("TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256"))
        assertTrue(profile.finalmask.startsWith("{\"tcp\":[{\"type\":\"fragment\",\"settings\":{\"packets\":\"tlshello\""))

        val config = config(variantA, "variant-a-fm")
        val out = proxy(config)
        assertTrue(out.contains("\"fingerprint\":\"unsafe\""))
        assertTrue(out.contains("\"cipherSuites\":\"${profile.cipherSuites}\""))
        assertTrue(out.contains("\"finalmask\":${profile.finalmask}"))
        assertTrue(config.contains("\"loglevel\":\"warning\""))
    }

    @Test
    fun variantBWritesEchDialsOwnAddressWithoutMux() {
        val profile = ProfileUriParser.parse(variantB)
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", profile.ech)
        val direct = DirectCompatProfileParser.parse(profile)!!
        assertEquals("188.114.98.0", direct.address)
        assertTrue(direct.identity.pinnedToOwnAddress)

        val config = config(variantB, "variant-b-ech")
        val out = proxy(config)
        assertTrue(out.contains("\"echConfigList\":\"cloudflare-ech.com+udp://1.1.1.1\""))
        assertTrue(out.contains("\"mux\":{\"enabled\":false"))
        assertTrue(out.contains("\"address\":\"188.114.98.0\""))
        assertTrue(config.contains("\"routeOnly\":true"))
    }

    @Test
    fun variantCKeepsIpv6Address() {
        val direct = DirectCompatProfileParser.parse(ProfileUriParser.parse(variantC))!!
        assertEquals("2a06:98c1:3101::6812:202f", direct.address)
        assertTrue(proxy(config(variantC, "variant-c-ipv6")).contains("\"address\":\"2a06:98c1:3101::6812:202f\""))
    }

    @Test
    fun trojanLineWithCsAndFmParses() {
        val profile = ProfileUriParser.parse(trojan)
        assertEquals(ProxyProtocol.TROJAN, profile.protocol)
        assertEquals("unsafe", profile.fingerprint)
        assertTrue(profile.finalmask.isNotBlank())
        assertTrue(proxy(config(trojan, "trojan-fm")).contains("\"finalmask\":{\"tcp\""))
    }

    @Test
    fun xhttpStreamUpLineParses() {
        val profile = ProfileUriParser.parse(xhttp)
        assertEquals("xhttp", profile.network)
        assertEquals("stream-up", profile.xhttpMode)
        val out = proxy(config(xhttp, "xhttp-fm"))
        assertTrue(out.contains("\"mode\":\"stream-up\""))
        assertTrue(out.contains("\"mux\":{\"enabled\":false"))
    }

    @Test
    fun junkKeysAreIgnoredAndEchclIsRead() {
        val profile = ProfileUriParser.parse(junk)
        assertEquals("snip.edgeoneai.cc.cd", profile.host)
        assertEquals("cloudflare-ech.com+udp://1.1.1.1", profile.ech)
        assertEquals("xudp", profile.packetEncoding)
        assertEquals("", profile.headerType)
    }

    @Test
    fun invalidFinalmaskIsDroppedNotFatal() {
        val profile = ProfileUriParser.parse("$ws&fp=chrome&fm=%7B%22tcp%22%3A%5B#Bad")
        assertEquals("", profile.finalmask)
    }

    @Test
    fun realityBuildsRealitySettings() {
        val profile = ProfileUriParser.parse(reality)
        assertEquals("reality", profile.security)
        val out = proxy(config(reality, "reality"))
        assertTrue(out.contains("\"realitySettings\":{\"serverName\":\"www.microsoft.com\",\"fingerprint\":\"firefox\"," +
            "\"publicKey\":\"Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw\",\"shortId\":\"6ba85179e30d4fc2\",\"spiderX\":\"/\"}"))
        assertFalse(out.contains("tlsSettings"))
        assertTrue(out.contains("\"mux\":{\"enabled\":false"))
    }

    @Test
    fun h2TransportGetsAClearError() {
        val h2 = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=tls&type=h2&sni=a.example.com#H2"
        val error = runCatching { ProfileUriParser.parse(h2) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("xhttp"))
        val coerced = runCatching { ProfileUriParser.parseForSniMaker(h2) }.exceptionOrNull()
        assertTrue(coerced?.message.orEmpty().contains("HTTP/2"))
    }

    @Test
    fun builtInsAreGoneAndSelectionsMigrateToAuto() {
        assertEquals(listOf(ProxyProfile.AUTO_ID), ProxyProfile.BUILT_IN_PROFILES.map { it.id })
        assertEquals(ProxyProfile.AUTO_ID, ProxyProfile.migrateSelectedId("builtin:mci2"))
        assertEquals(ProxyProfile.AUTO_ID, ProxyProfile.migrateSelectedId("builtin:mci"))
        assertEquals(ProxyProfile.AUTO_ID, ProxyProfile.migrateSelectedId("auto-run:abc"))
        assertEquals("profile:x", ProxyProfile.migrateSelectedId("profile:x"))
        assertEquals(ProxyProfile.AUTO, ProfileLibrary(emptyList(), "builtin:mci").selectedProfile)
        assertTrue(ProxyProfile.AUTO.isAuto)
    }
}