package com.uacspoofer.mobile.engine.pow

internal object PowTunRelayConfig {
    const val MIN_MTU = 1_200
    const val MAX_MTU = 1_280
    const val DEFAULT_MTU = 1_280
    const val FAKE_NET = "100.64.0.0"
    const val FAKE_MASK = "255.192.0.0"
    // Isolated to PoW — does not affect TorTunRelayConfig (its own yaml is untouched).
    const val CONNECT_TIMEOUT_MS = 2_000
    const val TCP_BUFFER_SIZE = 262_144
    const val UDP_TIMEOUT_MS = 20_000

    fun mtuForChain(preferred: Int, networkMtu: Int = 0): Int {
        var mtu = preferred.coerceIn(MIN_MTU, MAX_MTU)
        if (networkMtu in 576..9_000) {
            mtu = minOf(mtu, networkMtu.coerceAtLeast(MIN_MTU))
        }
        return mtu.coerceIn(MIN_MTU, MAX_MTU)
    }

    fun yaml(
        mtu: Int,
        socksPort: Int,
        tunIpv4: String,
        mapDns: String,
    ): String {
        val safeMtu = mtuForChain(mtu)
        val safePort = socksPort.coerceIn(1_024, 65_535)
        return """
            tunnel:
              name: tun0
              mtu: $safeMtu
              ipv4: '$tunIpv4'
              icmp: 'off'
            socks5:
              port: $safePort
              address: '127.0.0.1'
              udp: 'tcp'
            mapdns:
              address: '$mapDns'
              port: 53
              network: '$FAKE_NET'
              netmask: '$FAKE_MASK'
              cache-size: 10000
            misc:
              log-level: warn
              connect-timeout: $CONNECT_TIMEOUT_MS
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: $UDP_TIMEOUT_MS
              tcp-buffer-size: $TCP_BUFFER_SIZE
              task-stack-size: 262144
              max-session-count: 4096
              keepalive-timeout: 180000
        """.trimIndent() + "\n"
    }
}
