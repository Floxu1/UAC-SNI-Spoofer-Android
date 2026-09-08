package com.uacspoofer.mobile.engine.pow

internal object PowTunRelayConfig {
    const val MIN_MTU = 1_200
    const val MAX_MTU = 1_280
    const val DEFAULT_MTU = 1_280
    const val FAKE_NET = "100.64.0.0"
    const val FAKE_MASK = "255.192.0.0"

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
              connect-timeout: 15000
              tcp-read-write-timeout: 900000
              udp-read-write-timeout: 15000
              tcp-buffer-size: 524224
              task-stack-size: 557056
              max-session-count: 0
        """.trimIndent() + "\n"
    }
}
