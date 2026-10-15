package com.uacspoofer.mobile.engine.direct

/**
 * Engine 4 (UAC Direct): serverless direct DPI bypass. Nothing leaves through a server or a
 * worker; Xray dials every site directly and only reshapes the packets so SNI-based DPI misses
 * the hostname. Sites whose IP is blocked cannot be reached this way.
 */
data class DirectEngineSettings(
    /** "A" = first ClientHello stage 6/98/1, "B" = 0/104/1. Picking a variant resets that stage. */
    val variant: String = VARIANT_A,
    val helloLengths: String = HELLO_LENGTHS_A,
    val helloDelays: String = "0",
    val helloMaxSplit: Int = 0,
    val secondStageEnabled: Boolean = true,
    val secondLengths: String = "114,1",
    val secondDelays: String = "1",
    val secondMaxSplit: Int = 11,
    val tcpFragmentEnabled: Boolean = true,
    val tcpLengths: String = "1",
    val tcpDelays: String = "1",
    val tcpMaxSplit: Int = 201,
    val udpNoise: Boolean = true,
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
        unfilteredDns = unfilteredDns.trim().takeIf { it.startsWith("https://") || it.startsWith("tcp://") || it.isIpLiteral() }
            ?: DEFAULT_UNFILTERED_DNS,
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
        const val DEFAULT_UNFILTERED_DNS = "https://cloudflare-dns.com/dns-query"
        const val DEFAULT_DOMESTIC_DNS = "localhost"
        val DEFAULT = DirectEngineSettings()

        /** Accepts "6,98,1", "6 98 1" or ranges like "1-3"; anything else falls back. */
        internal fun cleanList(raw: String, fallback: String): String {
            val parts = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val ok = parts.isNotEmpty() && parts.all { Regex("^\\d{1,5}(-\\d{1,5})?$").matches(it) }
            return if (ok) parts.joinToString(",") else fallback
        }

        private fun String.isIpLiteral(): Boolean =
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(this) || (contains(':') && !contains('/'))
    }
}
