package com.uacspoofer.mobile.vpn

internal data class AdaptiveGateDecision(
    val accepted: Boolean,
    val mode: String,
)

internal fun decideAdaptiveGate(
    http: ProbeResult,
    dns: DnsProbeResult,
    tun: ProbeResult?,
    score: Int,
): AdaptiveGateDecision {
    val strongHttp = http.succeededTargets >= 2
    val httpWithDns = http.succeededTargets >= 1 && dns.success
    val tunReady = tun == null || tun.success || tun.hasSuccessfulPayload()
    val mode = when {
        strongHttp && dns.success && tunReady -> when {
            tun == null -> "dual-http+dns"
            tun.success -> "dual-http+dns+tun"
            else -> "dual-http+dns+tun-payload"
        }
        strongHttp && tunReady -> when {
            tun == null -> "dual-http+dns-degraded"
            tun.success -> "dual-http+tun+dns-degraded"
            else -> "dual-http+tun-payload+dns-degraded"
        }
        httpWithDns && tunReady -> when {
            tun == null -> "http+dns"
            tun.success -> "http+dns+tun"
            else -> "http+dns+tun-payload"
        }
        else -> "rejected"
    }
    val accepted = when (mode) {
        "dual-http+dns", "dual-http+dns+tun", "dual-http+dns+tun-payload",
        "dual-http+dns-degraded", "dual-http+tun+dns-degraded",
        "dual-http+tun-payload+dns-degraded" -> score >= 65
        "http+dns", "http+dns+tun", "http+dns+tun-payload" -> score >= 45
        else -> false
    }
    return AdaptiveGateDecision(accepted = accepted, mode = mode)
}

internal fun isRuntimeControlHealthy(
    proxyMode: Boolean,
    http: ProbeResult,
    dns: DnsProbeResult,
    tun: ProbeResult?,
): Boolean {
    val tunReady = tun == null || tun.success || tun.hasSuccessfulPayload()
    val dnsReady = dns.success || (!proxyMode && http.success && tunReady)
    return http.success && dnsReady && tunReady
}

internal fun candidateRouteSettleDelayMs(transport: String): Long = when (transport) {
    "cellular" -> 1_500L
    "wifi" -> 750L
    "ethernet" -> 500L
    else -> 1_000L
}

internal fun nextFailureCount(
    previousAtMs: Long,
    previousCount: Int,
    nowMs: Long,
    streakWindowMs: Long,
): Int = if (previousAtMs > 0L && nowMs - previousAtMs in 0L until streakWindowMs) {
    previousCount + 1
} else {
    1
}

internal fun isFailureCoolingDown(
    failedAtMs: Long,
    failureCount: Int,
    nowMs: Long,
    cooldownMs: Long,
    threshold: Int,
): Boolean = failureCount >= threshold &&
    failedAtMs > 0L && nowMs - failedAtMs in 0L until cooldownMs
