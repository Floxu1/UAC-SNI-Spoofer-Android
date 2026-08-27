package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCandidateSignatureTest {
    private val profile = ProxyProfile(
        id = "profile-1",
        name = "Profile",
        protocol = ProxyProtocol.VLESS,
        credential = "11111111-1111-1111-1111-111111111111",
        serverHost = "origin.example",
        serverPort = 443,
        network = "ws",
        security = "tls",
        sni = "cdn.example",
        host = "cdn.example",
        path = "/ws",
        alpn = "",
        fingerprint = "chrome",
        country = CountryMetadata.UNKNOWN,
    )

    @Test
    fun runtimeIdentityChangesInvalidateThePlanSignature() {
        val settings = AdvancedSettingsData.DEFAULT
        val baseline = AdaptiveCandidatePlanner.signatureFor(settings, profile)

        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(credential = "changed")))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(sni = "other.example")))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(host = "other.example")))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(path = "/other")))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(alpn = "h2")))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(fingerprint = "firefox")))
    }

    @Test
    fun effectiveSettingsChangesInvalidateThePlanSignature() {
        val settings = AdvancedSettingsData.DEFAULT
        val baseline = AdaptiveCandidatePlanner.signatureFor(settings, profile)

        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings.copy(finalmaskDelayMs = 20), profile))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings.copy(dnsResolverUrl = AdaptiveDnsResolvers.GOOGLE.url), profile))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings.copy(blockUdp443 = true), profile))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings.copy(tunMtu = 1400), profile))
    }

    @Test
    fun presentationOnlyChangesKeepThePlanSignatureStable() {
        val settings = AdvancedSettingsData.DEFAULT
        val baseline = AdaptiveCandidatePlanner.signatureFor(settings, profile)

        assertEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, profile.copy(name = "Renamed")))
        assertEquals(
            baseline,
            AdaptiveCandidatePlanner.signatureFor(
                settings,
                profile.copy(country = CountryMetadata.resolve("DE", "Germany")),
            ),
        )
    }

    @Test
    fun realDirectEndpointAndIdentityChangeThePlanSignature() {
        val settings = AdvancedSettingsData.DEFAULT
        val first = directCompatProfile("origin-a.example", 443, "")
        val changedAddress = directCompatProfile("origin-b.example", 443, "")
        val changedPort = directCompatProfile("origin-a.example", 8443, "")
        val changedDirectAlpn = directCompatProfile("origin-a.example", 443, "h2")
        val baseline = AdaptiveCandidatePlanner.signatureFor(settings, first)

        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, changedAddress))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, changedPort))
        assertNotEquals(baseline, AdaptiveCandidatePlanner.signatureFor(settings, changedDirectAlpn))
    }

    @Test
    fun importedPublicEndpointChangesThePlanSignature() {
        val settings = AdvancedSettingsData.DEFAULT
        val first = localForwardImportedProfile("origin-a.example", 443, "")
        val changedAddress = localForwardImportedProfile("origin-b.example", 443, "")
        assertNotEquals(
            AdaptiveCandidatePlanner.signatureFor(settings, first),
            AdaptiveCandidatePlanner.signatureFor(settings, changedAddress),
        )
    }

    @Test
    fun savedRoutesDoNotConsumeTheAdaptiveRescueBudget() {
        val settings = AdvancedSettingsData.DEFAULT
        val raw = (1..AdaptiveCandidatePlanner.MAX_CANDIDATES).map { index ->
            candidate("raw-$index", settings)
        }
        val champion = candidate("saved-champion", settings).copy(learned = true)
        val backup = candidate("saved-backup", settings)

        val ordered = prioritizeAdaptiveCandidates(
            raw = raw,
            savedRoute = champion,
            savedBackupRoute = backup,
            learnedId = null,
            maxAdaptiveCandidates = AdaptiveCandidatePlanner.MAX_CANDIDATES,
        )

        assertEquals(AdaptiveCandidatePlanner.MAX_CANDIDATES + 2, ordered.size)
        assertEquals(listOf(champion.id, backup.id), ordered.take(2).map(AdaptiveCandidate::id))
        assertEquals(raw.map(AdaptiveCandidate::id).toSet(), ordered.drop(2).map(AdaptiveCandidate::id).toSet())
    }

    @Test
    fun onlyTrueDirectCompatCandidateIsPersistedAsDirect() {
        val imported = directCompatProfile("origin-a.example", 443, "")
        val settings = AdvancedSettingsData.DEFAULT
        val edge = MciEdge("origin-a.example", 443, "origin", 2)
        val identity = requireNotNull(com.uacspoofer.mobile.profiles.DirectCompatProfileParser.parse(imported)).identity
        val direct = AdaptiveCandidate(
            id = "direct",
            label = "direct",
            edge = edge,
            settings = settings,
            runtimeOptions = MciXrayRuntimeOptions(
                identityOverride = identity,
                finalmaskEnabled = false,
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            ),
        )
        val fragmentedOriginalEndpoint = direct.copy(
            id = "fragmented",
            runtimeOptions = direct.runtimeOptions.copy(finalmaskEnabled = true),
        )

        assertTrue(direct.isDirectCompatRoute(imported))
        assertFalse(fragmentedOriginalEndpoint.isDirectCompatRoute(imported))
    }

    private fun directCompatProfile(address: String, port: Int, rawAlpn: String): ProxyProfile {
        val alpn = rawAlpn.takeIf { it.isNotBlank() }?.let { "&alpn=$it" }.orEmpty()
        return profile.copy(
            serverHost = address,
            serverPort = port,
            alpn = "http/1.1",
            rawUri = "vless://${profile.credential}@$address:$port" +
                "?type=ws&security=tls&sni=cdn.example&host=cdn.example&path=%2Fws$alpn#Profile",
        )
    }

    private fun localForwardImportedProfile(address: String, port: Int, rawAlpn: String): ProxyProfile {
        val alpn = rawAlpn.takeIf { it.isNotBlank() }?.let { "&alpn=$it" }.orEmpty()
        return profile.copy(
            serverHost = "127.0.0.1",
            serverPort = 40_443,
            alpn = "http/1.1",
            rawUri = "vless://${profile.credential}@$address:$port" +
                "?type=ws&security=tls&sni=cdn.example&host=cdn.example&path=%2Fws$alpn#Profile",
        )
    }

    private fun candidate(id: String, settings: AdvancedSettingsData): AdaptiveCandidate = AdaptiveCandidate(
        id = id,
        label = id,
        edge = MciEdge("104.18.1.1", 443, id, 2),
        settings = settings,
        runtimeOptions = MciXrayRuntimeOptions.DEFAULT,
    )
}
