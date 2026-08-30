package com.uacspoofer.mobile.engine.tor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTunnelBridgeParserTest {
    @Test
    fun parsesWebTunnelLinesAndIgnoresXrayUris() {
        val raw = """
            # comment
            webtunnel 192.0.2.10:443 url=https://cdn.example/secret
            Bridge webtunnel [2001:db8::1]:443 url=https://edge.example/path
            vless://uuid@example.com:443?type=xhttp
            webtunnel 10.0.0.1:443
            snowflake 192.0.2.4:80
        """.trimIndent()

        val bridges = WebTunnelBridgeParser.parseAll(raw)
        assertEquals(2, bridges.size)
        assertEquals("192.0.2.10", bridges[0].address)
        assertEquals(443, bridges[0].port)
        assertEquals("https://cdn.example/secret", bridges[0].url)
        assertEquals("2001:db8::1", bridges[1].address)
        val fingerprinted = WebTunnelBridgeParser.parseLine(
            "webtunnel [2001:db8:63b4:6bd7:a357:7858:898e:718c]:443 059AEB126918A33B8246E8136D565FF57753D5CF url=https://alwaysnewbie.eu.org/homesweethome ver=0.0.2",
        )
        assertNotNull(fingerprinted)
        assertEquals("2001:db8:63b4:6bd7:a357:7858:898e:718c", fingerprinted!!.address)
        assertEquals("https://alwaysnewbie.eu.org/homesweethome", fingerprinted.url)
        assertTrue(fingerprinted.torrcLine().startsWith("Bridge webtunnel "))
        assertTrue(fingerprinted.torrcLine().contains("192.0.2."))
        assertFalse(fingerprinted.torrcLine().contains("2001:db8"))
        assertNull(WebTunnelBridgeParser.parseLine("vless://uuid@cf.example:443"))
        assertNull(WebTunnelBridgeParser.parseLine("webtunnel 10.0.0.1:443"))
    }
}

class TorRcWriterTest {
    @Test
    fun classicTorOmitsBridges() {
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT,
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = null,
            bridges = emptyList(),
        )
        assertTrue(rc.contains("SocksPort 127.0.0.1:19050"))
        assertFalse(rc.contains("IsolateDestAddr"))
        assertTrue(rc.contains("CircuitBuildTimeout 20"))
        assertTrue(rc.contains("ControlPort 127.0.0.1:19051"))
        assertTrue(rc.contains("ClientOnly 1"))
        assertTrue(rc.contains("RunAsDaemon 0"))
        assertFalse(rc.contains("Sandbox"))
        assertFalse(rc.contains("ClientUseIPv6"))
        assertFalse(rc.contains("ReachableAddresses"))
        assertFalse(rc.contains("OwningControllerProcess"))
        assertFalse(rc.contains("UseBridges"))
        assertFalse(rc.contains("ClientTransportPlugin"))
        assertTrue(rc.contains("uac-webtunnel-fragment"))
    }

    @Test
    fun webtunnelBridgesRequirePluginAndStayIsolated() {
        val plugin = File("/tmp/libwebtunnel.so")
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 url=https://cdn.example/secret",
        )
        assertNotNull(bridge)
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(fragmentEnabled = false),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = plugin,
            bridges = listOf(bridge!!),
        )
        assertTrue(rc.contains("UseBridges 1"))
        assertTrue(rc.contains("UpdateBridgesFromAuthority 0"))
        assertFalse(rc.contains("UseEntryGuards 0"))
        assertTrue(rc.contains("ClientTransportPlugin webtunnel exec ${plugin.absolutePath}"))
        assertTrue(rc.contains("Bridge webtunnel 192.0.2.10:443 url=https://cdn.example/secret"))
        assertTrue(rc.contains("ClientUseIPv6 0"))
        assertFalse(rc.contains("EntryNodes"))
        assertFalse(rc.contains("uac-webtunnel-fragment"))
        assertFalse(rc.contains("vless"))
        assertFalse(rc.contains("cloudflare", ignoreCase = true))
    }

    @Test
    fun automaticOmitsExitNodes() {
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT,
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = null,
            bridges = emptyList(),
        )
        assertFalse(rc.contains("ExitNodes"))
        assertFalse(rc.contains("StrictNodes"))
        assertFalse(rc.contains("EntryNodes"))
        assertFalse(rc.contains("GeoIPFile"))
    }

    @Test
    fun preferExitCountryWritesGeoipAndLooseExitNodes() {
        val geoip = File("/tmp/geoip")
        val geoip6 = File("/tmp/geoip6")
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(exitCountryCode = "DE", exitStrict = false),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = null,
            bridges = emptyList(),
            geoIpFile = geoip,
            geoIp6File = geoip6,
        )
        assertTrue(rc.contains("GeoIPFile ${geoip.absolutePath}"))
        assertTrue(rc.contains("GeoIPv6File ${geoip6.absolutePath}"))
        assertTrue(rc.contains("ExitNodes {de}"))
        assertTrue(rc.contains("StrictNodes 0"))
        assertFalse(rc.contains("EntryNodes"))
    }

    @Test
    fun mustExitCountrySetsStrictNodesWithoutPinningEntry() {
        val plugin = File("/tmp/libwebtunnel.so")
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 url=https://cdn.example/secret",
        )!!
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(
                fragmentEnabled = false,
                exitCountryCode = "nl",
                exitStrict = true,
            ),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = plugin,
            bridges = listOf(bridge),
            geoIpFile = File("/tmp/geoip"),
            geoIp6File = File("/tmp/geoip6"),
        )
        assertTrue(rc.contains("UseBridges 1"))
        assertTrue(rc.contains("ExitNodes {nl}"))
        assertTrue(rc.contains("StrictNodes 1"))
        assertFalse(rc.contains("EntryNodes"))
        assertFalse(rc.contains("UseEntryGuards 0"))
    }

    @Test
    fun batchBridgesStayOnOneTorrc() {
        val plugin = File("/tmp/webtunnel")
        val first = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000011111 url=https://a.example/x",
        )!!
        val second = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.11:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222 url=https://b.example/y",
        )!!
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(fragmentEnabled = false),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = plugin,
            bridges = listOf(first, second),
        )
        assertFalse(rc.contains("Sandbox"))
        assertEquals(2, Regex("^Bridge webtunnel ", RegexOption.MULTILINE).findAll(rc).count())
    }

    @Test
    fun ipv6PlaceholderBridgesAreRewrittenToIpv4() {
        val plugin = File("/tmp/libwebtunnel.so")
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel [2001:db8:63b4:6bd7:a357:7858:898e:718c]:443 059AEB126918A33B8246E8136D565FF57753D5CF url=https://alwaysnewbie.eu.org/homesweethome ver=0.0.2",
        )!!
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(fragmentEnabled = false),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = plugin,
            bridges = listOf(bridge),
        )
        assertFalse(rc.contains("2001:db8"))
        assertTrue(rc.contains("Bridge webtunnel 192.0.2."))
        assertTrue(rc.contains("ClientUseIPv6 0"))
        assertFalse(rc.contains("ReachableAddresses"))
    }

    @Test
    fun androidNativePluginPathWithEqualsStaysUnquoted() {
        val plugin = File("/data/app/com.uacspoofer.mobile-abc==/lib/x86_64/libwebtunnel.so")
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 url=https://cdn.example/secret",
        )!!
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(fragmentEnabled = false),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = plugin,
            bridges = listOf(bridge),
            pluginExecTokens = listOf(plugin.absolutePath),
        )
        assertTrue(rc.contains("ClientTransportPlugin webtunnel exec ${plugin.absolutePath}"))
        assertFalse(rc.contains("exec \""))
    }

    @Test
    fun linkerPrefixedPluginStaysSpaceFreeTokens() {
        val plugin = File("/tmp/libwebtunnel.so")
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 url=https://cdn.example/secret",
        )!!
        val rc = TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT.copy(fragmentEnabled = false),
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = plugin,
            bridges = listOf(bridge),
            pluginExecTokens = listOf("/system/bin/linker64", plugin.absolutePath),
        )
        assertTrue(rc.contains("ClientTransportPlugin webtunnel exec /system/bin/linker64 ${plugin.absolutePath}"))
    }

    @Test(expected = IllegalStateException::class)
    fun pluginPathWithSpacesFailsClosed() {
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 url=https://cdn.example/secret",
        )!!
        TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT,
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = File("/tmp/Onion Hop/webtunnel"),
            bridges = listOf(bridge),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun missingPluginWithBridgesFailsClosed() {
        val bridge = WebTunnelBridgeParser.parseLine(
            "webtunnel 192.0.2.10:443 url=https://cdn.example/secret",
        )!!
        TorRcWriter.render(
            settings = TorEngineSettings.DEFAULT,
            dataDirectory = File("/tmp/tor-data"),
            webtunnelPlugin = null,
            bridges = listOf(bridge),
        )
    }
}

class TorControlClientTest {
    @Test
    fun parsesBootstrapProgressFromControlSpec() {
        val body = """
            250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=80 TAG=handshake_done SUMMARY="Done"
            250 OK
        """.trimIndent()
        assertEquals(80, TorControlClient.parseBootstrapProgress(body))
        assertEquals(100, TorControlClient.parseBootstrapProgress("PROGRESS=100"))
        assertNull(TorControlClient.parseBootstrapProgress("250 OK"))
    }
}

class TorLaunchArgsTest {
    @Test
    fun guardianStyleArgvHasNoVerifyLogOrControlPort() {
        val torrc = File("/tmp/torrc")
        val defaults = File("/tmp/torrc-defaults")
        val data = File("/tmp/tor-data")
        val cache = File("/tmp/tor-cache")
        val socket = File("/tmp/tor-data/ControlSocket")
        val run = TorLaunchArgs.commandLine(
            torrc = torrc,
            defaultsTorrc = defaults,
            dataDir = data,
            cacheDir = cache,
            controlSocket = socket,
        )
        assertEquals("tor", run[0])
        assertFalse(run.contains("--verify-config"))
        assertFalse(run.contains("--ControlPort"))
        assertFalse(run.contains("--Log"))
        assertTrue(run.contains("--RunAsDaemon"))
        assertTrue(run.contains("--DataDirectory"))
        assertTrue(run.contains("--ControlSocket"))
        assertTrue(run.contains("--CookieAuthentication"))
        val verify = TorLaunchArgs.commandLine(
            torrc = torrc,
            defaultsTorrc = defaults,
            dataDir = data,
            cacheDir = cache,
            controlSocket = socket,
            verifyConfig = true,
        )
        assertEquals("--verify-config", verify[1])
        assertEquals(run.size + 1, verify.size)
    }
}

class TorExecTest {
    @Test
    fun argvDefaultsToDirectExec() {
        val binary = File("webtunnel")
        assertEquals(listOf(binary.absolutePath), TorExec.argv(binary))
        assertEquals(
            listOf(binary.absolutePath, "-f", "rc"),
            TorExec.argv(binary, extraArgs = listOf("-f", "rc")),
        )
    }

    @Test
    fun argvWithLinkerPrefixesTheSystemLinker() {
        val binary = File("webtunnel")
        val rc = File("torrc")
        assertEquals(
            listOf("/system/bin/linker64", binary.absolutePath, "-f", rc.absolutePath),
            TorExec.argv(
                binary = binary,
                extraArgs = listOf("-f", rc.absolutePath),
                linker = "/system/bin/linker64",
            ),
        )
    }
}

class TorTunRelayConfigTest {
    @Test
    fun tunRelayIsSocksOnlyAndNeverCloudflare() {
        val yaml = TorTunRelayConfig.yaml(mtu = 1280, socksPort = TorEngineSettings.SOCKS_PORT)
        assertTrue(yaml.contains("address: '127.0.0.1'"))
        assertTrue(yaml.contains("port: ${TorEngineSettings.SOCKS_PORT}"))
        assertTrue(yaml.contains("mapdns:"))
        assertTrue(yaml.contains("address: '${TorTunRelayConfig.MAP_DNS}'"))
        assertTrue(yaml.contains("cache-size: 10000"))
        assertFalse(yaml.contains("vless", ignoreCase = true))
        assertFalse(yaml.contains("trojan", ignoreCase = true))
        assertFalse(yaml.contains("xhttp", ignoreCase = true))
        assertFalse(yaml.contains("cloudflare", ignoreCase = true))
        assertFalse(yaml.contains("reality", ignoreCase = true))
        assertFalse(yaml.contains("fakedns"))
        assertFalse(yaml.contains("libv2ray"))
        assertFalse(yaml.contains("xray", ignoreCase = true))
        assertFalse(yaml.contains("ipv6:"))
    }
}

class TorEngineSettingsTest {
    @Test
    fun validatedKeepsControlPortAwayFromSocks() {
        val settings = TorEngineSettings(socksPort = 19050, controlPort = 19050, fragmentLength = 0).validated()
        assertEquals(19050, settings.socksPort)
        assertEquals(19051, settings.controlPort)
        assertEquals(1, settings.fragmentLength)
    }

    @Test
    fun validatedNormalizesExitCountryAndDropsUnknownCodes() {
        assertEquals("de", TorEngineSettings(exitCountryCode = "DE").validated().exitCountryCode)
        assertEquals("", TorEngineSettings(exitCountryCode = "uk").validated().exitCountryCode)
        assertEquals("", TorEngineSettings(exitCountryCode = "deu").validated().exitCountryCode)
        assertTrue(TorEngineSettings(exitCountryCode = "nl", exitStrict = true).validated().exitStrict)
    }
}

class TorExitCountryTest {
    @Test
    fun recommendedListHasMajorExitCountries() {
        assertTrue(TorExitCountry.RECOMMENDED.containsAll(listOf("de", "nl", "us", "fr", "gb")))
        assertFalse(TorExitCountry.RECOMMENDED.contains("ir"))
    }

    @Test
    fun automaticResetsExitNodesOnControlPort() {
        assertEquals(
            listOf("RESETCONF ExitNodes StrictNodes", "SIGNAL NEWNYM"),
            TorExitCountry.controlCommands("", false),
        )
    }

    @Test
    fun countryPinUsesQuotedExitNodesAndNewNym() {
        assertEquals(
            listOf(
                """SETCONF ExitNodes="{de}"""",
                "SETCONF StrictNodes=0",
                "SIGNAL NEWNYM",
            ),
            TorExitCountry.controlCommands("DE", false),
        )
        assertEquals(
            listOf(
                """SETCONF ExitNodes="{nl}"""",
                "SETCONF StrictNodes=1",
                "SIGNAL NEWNYM",
            ),
            TorExitCountry.controlCommands("nl", true),
        )
        assertTrue(TorExitCountry.exitNodesConfigured("250-ExitNodes={de}\n250 OK\n", "DE"))
        assertFalse(TorExitCountry.exitNodesConfigured("250-ExitNodes={de}\n250 OK\n", "ca"))
        assertTrue(TorExitCountry.exitNodesConfigured("250 ExitNodes\n250 OK\n", ""))
        assertEquals(
            listOf("3", "12"),
            TorControlClient.parseBuiltCircuitIds(
                """
                250+circuit-status=
                1 LAUNCHED ${'$'}abc PURPOSE=GENERAL
                3 BUILT ${'$'}def PURPOSE=GENERAL TIME_CREATED=2026-01-01T00:00:00.000000
                12 BUILT ${'$'}ghi PURPOSE=GENERAL
                9 FAILED ${'$'}jkl PURPOSE=GENERAL
                .
                250 OK
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun controlErrorsAreDetectedFromFiveHundredReplies() {
        assertTrue(TorControlClient.isControlError("552 Unrecognized option: ExitNodes\n"))
        assertTrue(TorControlClient.isControlError("250 OK\n551 Rate limited\n"))
        assertFalse(TorControlClient.isControlError("250 OK\n"))
        assertFalse(TorControlClient.isControlError("250-ExitNodes={de}\n250 OK\n"))
    }
}

class WebTunnelHandshakeTest {
    @Test
    fun switchingProtocolsIsExactHttp101() {
        assertTrue(WebTunnelHandshake.isSwitchingProtocols("HTTP/1.1 101 Switching Protocols"))
        assertTrue(WebTunnelHandshake.isSwitchingProtocols("HTTP/1.0 101"))
        assertFalse(WebTunnelHandshake.isSwitchingProtocols("HTTP/1.1 200 OK"))
        assertFalse(WebTunnelHandshake.isSwitchingProtocols("HTTP/1.1 502 Bad Gateway"))
        assertFalse(WebTunnelHandshake.isSwitchingProtocols(""))
    }
}

class TorGeoIpDecodeTest {
    @Test
    fun decodePassesThroughRawGeoip() {
        val raw = "# geoip\n1,2,US\n".toByteArray()
        val out = TorGeoIp.decode(raw.inputStream()).readBytes()
        assertEquals(raw.toString(Charsets.UTF_8), out.toString(Charsets.UTF_8))
    }

    @Test
    fun decodeUnwrapsGzipGeoip() {
        val raw = "# geoip\n1,2,DE\n".toByteArray()
        val gz = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gz).use { it.write(raw) }
        val out = TorGeoIp.decode(gz.toByteArray().inputStream()).readBytes()
        assertEquals(raw.toString(Charsets.UTF_8), out.toString(Charsets.UTF_8))
    }
}

class WebTunnelBridgeCatalogTest {
    @Test
    fun emptyPasteUsesBundledAndPutsLastGoodFirst() {
        val bundled = listOf(
            WebTunnelBridgeParser.parseLine(
                "webtunnel 192.0.2.10:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000011111 url=https://a.example/x",
            )!!,
            WebTunnelBridgeParser.parseLine(
                "webtunnel 192.0.2.11:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000022222 url=https://b.example/y",
            )!!,
        )
        val last = bundled[1].raw
        val merged = WebTunnelBridgeCatalog.merge("", last, bundled)
        assertEquals(bundled[1].raw, merged.first().raw)
        assertEquals(2, merged.size)
    }

    @Test
    fun pastedBridgesDoNotPullBundledList() {
        val bundled = listOf(
            WebTunnelBridgeParser.parseLine(
                "webtunnel 192.0.2.10:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000011111 url=https://a.example/x",
            )!!,
        )
        val merged = WebTunnelBridgeCatalog.merge(
            "webtunnel 192.0.2.99:443 AAAAABBBBBCCCCCDDDDDEEEEEFFFFF0000099999 url=https://mine.example/z",
            "",
            bundled,
        )
        assertEquals(1, merged.size)
        assertEquals("192.0.2.99", merged[0].address)
    }
}
