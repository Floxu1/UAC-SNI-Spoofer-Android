package com.uacspoofer.mobile.engine.direct

/**
 * Engine 4 (UAC Direct): serverless. Layers (all local, no owned server / worker):
 * 1) Google MITM domain-fronting (SNI www.google.com) for Google/YouTube — needs user CA.
 * 2) Cloudflare ECH (outer SNI cloudflare-ech.com) for CF-hosted sites — needs user CA.
 * 3) Optional ClientHello fragment (off by default: MCI drops fragmented hellos).
 * 4) Direct path for Iranian / private destinations.
 */
data class DirectEngineSettings(
    val googleFronting: Boolean = true,
    val echEnabled: Boolean = true,
    val fragmentEnabled: Boolean = false,
    val variant: String = VARIANT_A,
    val helloLengths: String = HELLO_LENGTHS_A,
    val helloDelays: String = "0",
    val helloMaxSplit: Int = 0,
    val secondStageEnabled: Boolean = true,
    val secondLengths: String = "114,1",
    val secondDelays: String = "1",
    val secondMaxSplit: Int = 11,
    val tcpFragmentEnabled: Boolean = false,
    val tcpLengths: String = "1",
    val tcpDelays: String = "1",
    val tcpMaxSplit: Int = 201,
    val udpNoise: Boolean = false,
    val blockQuic: Boolean = true,
    val ipv6: Boolean = false,
    val iranDirect: Boolean = true,
    val unfilteredDns: String = DEFAULT_UNFILTERED_DNS,
    val domesticDns: String = DEFAULT_DOMESTIC_DNS,
) {
    fun validated(): DirectEngineSettings = copy(
        variant = if (variant == VARIANT_B) VARIANT_B else VARIANT_A,
        helloLengths = cleanList(helloLengths, HELLO_LENGTHS_A),
        helloDelays = cleanList(helloDelays, "0"),
        helloMaxSplit = helloMaxSplit.coerceIn(0, 1000),
        secondLengths = cleanList(secondLengths, "114,1"),
        secondDelays = cleanList(secondDelays, "1"),
        secondMaxSplit = secondMaxSplit.coerceIn(0, 1000),
        tcpLengths = cleanList(tcpLengths, "1"),
        tcpDelays = cleanList(tcpDelays, "1"),
        tcpMaxSplit = tcpMaxSplit.coerceIn(0, 1000),
        unfilteredDns = unfilteredDns.trim().takeIf {
            it.startsWith("https://") || it.startsWith("h2c://") || it.startsWith("tcp://") || it.isIpLiteral()
        } ?: DEFAULT_UNFILTERED_DNS,
        domesticDns = domesticDns.trim().ifBlank { DEFAULT_DOMESTIC_DNS },
    )

    fun withVariant(v: String): DirectEngineSettings = when (v) {
        VARIANT_B -> copy(variant = VARIANT_B, helloLengths = HELLO_LENGTHS_B)
        else -> copy(variant = VARIANT_A, helloLengths = HELLO_LENGTHS_A)
    }

    companion object {
        const val VARIANT_A = "A"
        const val VARIANT_B = "B"
        const val HELLO_LENGTHS_A = "6,98,1"
        const val HELLO_LENGTHS_B = "0,104,1"
        /** DoH that MITM will front through Google/Microsoft SNI. */
        const val DEFAULT_UNFILTERED_DNS = "h2c://1.1.1.1/dns-query"
        const val DEFAULT_DOMESTIC_DNS = "localhost"
        /**
         * Cloudflare ECH config list (HTTPS RR of cloudflare-ech.com, public_name=cloudflare-ech.com).
         * Hardcoded so ECH works without foreign DNS. Refresh periodically from a fronted DoH path.
         */
        const val CLOUDFLARE_ECH_CONFIG_LIST =
            "AEX+DQBBfwAgACB2BqvhWR0vj3W6kSImwai9TTXubeU10aiaBaAZ27u6EAAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="
        val DEFAULT = DirectEngineSettings()

        internal fun cleanList(raw: String, fallback: String): String {
            val parts = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val ok = parts.isNotEmpty() && parts.all { Regex("^\\d{1,5}(-\\d{1,5})?$").matches(it) }
            return if (ok) parts.joinToString(",") else fallback
        }

        private fun String.isIpLiteral(): Boolean =
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(this) || (contains(':') && !contains('/'))
    }
}
