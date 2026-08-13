package com.uacspoofer.mobile.mci

data class MciEdge(
    val address: String,
    val port: Int,
    val role: String,
    val finalmaskMaxSplit: Int = 2,
)

data class ProbeTarget(
    val name: String,
    val url: String,
)

object MciConfig {
    const val CARRIER_ID = "mci"

    
    val PRIMARY_EDGE = MciEdge("104.18.1.1", 443, "primary")
    val FALLBACK_EDGE = MciEdge("172.66.0.1", 443, "fallback")
    
    
    val IRANCELL_EDGE = MciEdge("104.18.1.1", 443, "irancell", finalmaskMaxSplit = 100)
    val EDGES = listOf(PRIMARY_EDGE, IRANCELL_EDGE, FALLBACK_EDGE)

    
    const val PATTERN_FAKE_SNI = "www.speedtest.net"
    const val PATTERN_FAKE_REPEAT = 1
    const val PROTECTED_INJECT_DELAY_MS = 0L
    const val PROTECTED_ROUTE_STRATEGY = "plain"

    
    const val TLS_SERVER_NAME = "www.ignitelimit.com"
    const val WEBSOCKET_HOST = "www.ignitelimit.com"
    const val WEBSOCKET_PATH = "/assignment"
    const val TROJAN_PASSWORD = "humanity"

    const val LOCAL_BRIDGE_ADDRESS = "127.0.0.1"
    const val LOCAL_BRIDGE_PORT = 40443
    const val LOCAL_SOCKS_ADDRESS = "127.0.0.1"
    const val LOCAL_SOCKS_PORT = 10808

    
    const val FINALMASK_PACKET = "tlshello"
    const val FINALMASK_LENGTH = 5
    const val FINALMASK_DELAY_MS = 0
    const val FINALMASK_MAX_SPLIT = 2
    const val XRAY_KEEPALIVE_IDLE_SECONDS = 11
    const val XRAY_KEEPALIVE_INTERVAL_SECONDS = 1

    
    const val CONNECT_TIMEOUT_MS = 5_000
    const val PATTERN_ACK_TIMEOUT_MS = 8_000
    const val RELAY_BUFFER_BYTES = 256 * 1024
    const val SOCKET_BUFFER_BYTES = 512 * 1024
    const val PATTERN_MAX_SESSIONS = 4
    const val BRIDGE_MAX_SESSIONS = 64
    const val EDGE_FAILURE_COOLDOWN_MS = 12_000L

    
    const val TUN_MTU = 1280
    const val TUN_ADDRESS = "198.18.0.1"

    const val PROBE_MIN_BYTES_PER_TARGET = 64
    const val PROBE_READ_BYTES_PER_TARGET = 512
    const val PROBE_MIN_TOTAL_BYTES = 1_024
    const val PROBE_TOTAL_TIMEOUT_MS = 10_000L
    const val HEALTH_CHECK_INTERVAL_MS = 30_000L
    const val CONNECT_PROBE_ATTEMPTS = 3
    const val CONNECT_PROBE_RETRY_DELAY_MS = 1_500L
    const val RUNTIME_HEALTH_MAX_FAILURES = 3
    const val RUNTIME_HEALTH_RETRY_DELAY_MS = 2_500L
    const val RUNTIME_RECOVERY_BACKOFF_MS = 1_000L

    val PROBE_TARGETS = listOf(
        ProbeTarget("Google", "https://www.google.com/robots.txt"),
        ProbeTarget("YouTube", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
        ProbeTarget("Telegram", "https://telegram.org/img/t_logo.png"),
    )
}
