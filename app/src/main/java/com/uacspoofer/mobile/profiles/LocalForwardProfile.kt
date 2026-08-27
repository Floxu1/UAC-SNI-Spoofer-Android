package com.uacspoofer.mobile.profiles

import com.uacspoofer.mobile.settings.AdvancedSettingsData

/**
 * Profiles imported from desktop/subscription URIs keep a loopback endpoint
 * ({@link HOST}:{@link PORT}) for the local bridge, while Xray must route through
 * Cloudflare edge addresses using the TLS identity from the original URI.
 */
object LocalForwardProfile {
    const val HOST = "127.0.0.1"
    const val PORT = 40443
    const val ROUTING_PORT = 443

    fun isLocalForward(profile: ProxyProfile): Boolean {
        val storedLoopback = profile.serverPort == PORT &&
            (profile.serverHost.equals(HOST, ignoreCase = true) ||
                profile.serverHost.equals("localhost", ignoreCase = true))
        if (!storedLoopback) return false
        val original = DirectCompatProfileParser.parseRaw(profile) ?: return true
        return original.address.equals(HOST, ignoreCase = true) ||
            original.address.equals("localhost", ignoreCase = true)
    }

    fun routingIdentity(profile: ProxyProfile, settings: AdvancedSettingsData): RuntimeProxyIdentity =
        DirectCompatProfileParser.parseIdentity(profile) ?: profile.runtimeIdentity(settings)

    fun routingEndpointKey(profile: ProxyProfile): String {
        val host = profile.sni.ifBlank { profile.host }.ifBlank { "edge-routed" }
        return "$host:$ROUTING_PORT"
    }
}
