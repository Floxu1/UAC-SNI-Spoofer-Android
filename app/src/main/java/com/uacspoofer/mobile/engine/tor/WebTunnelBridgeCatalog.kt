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

    fun merge(
        userLines: String,
        lastGoodRaw: String,
        bundled: List<WebTunnelBridge>,
    ): List<WebTunnelBridge> {
        val configured = WebTunnelBridgeParser.parseAll(userLines)
        val lastGood = WebTunnelBridgeParser.parseLine(lastGoodRaw)
        val extras = if (configured.isEmpty()) bundled.shuffled() else emptyList()
        return buildList {
            if (lastGood != null) add(lastGood)
            addAll(configured)
            addAll(extras)
        }.distinctBy { it.raw.lowercase() }
    }
}
