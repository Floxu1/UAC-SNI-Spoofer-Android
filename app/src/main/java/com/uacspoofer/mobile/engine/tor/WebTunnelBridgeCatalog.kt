package com.uacspoofer.mobile.engine.tor

import android.content.Context

internal object WebTunnelBridgeCatalog {
    const val ASSET_PATH = "tor/bridges-webtunnel.txt"

    fun loadBundled(context: Context): List<WebTunnelBridge> {
        val text = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return WebTunnelBridgeParser.parseAll(text)
    }

    /**
     * Last-good first, then pasted lines, then bridges refreshed through Tor, then the
     * bundled list (always included so a stale paste never strands the user).
     */
    fun merge(
        userLines: String,
        lastGoodRaw: String,
        bundled: List<WebTunnelBridge>,
        refreshed: List<WebTunnelBridge> = emptyList(),
    ): List<WebTunnelBridge> {
        val configured = WebTunnelBridgeParser.parseAll(userLines)
        val lastGood = WebTunnelBridgeParser.parseLine(lastGoodRaw)
        return buildList {
            if (lastGood != null) add(lastGood)
            addAll(configured)
            addAll(refreshed.shuffled())
            addAll(bundled.shuffled())
        }.distinctBy { it.raw.lowercase() }
    }
}