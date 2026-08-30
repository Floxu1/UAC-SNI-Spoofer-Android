package com.uacspoofer.mobile.engine.tor

internal object TorTunRelayConfig {
    const val MAP_DNS = "198.18.0.2"
    const val FAKE_NET = "100.64.0.0"
    const val FAKE_MASK = "255.192.0.0"

    fun yaml(
        mtu: Int,
        socksPort: Int,
        tunIpv4: String = "198.18.0.1",
        mapDns: String = MAP_DNS,
    ): String {
        val safeMtu = mtu.coerceIn(1280, 1500)
        val safePort = socksPort.coerceIn(1024, 65_535)
        return """
            tunnel:
              name: tun0
              mtu: $safeMtu
              ipv4: '$tunIpv4'
              icmp: 'off'
            socks5:
              port: $safePort
              address: '127.0.0.1'
              udp: 'udp'
            mapdns:
              address: '$mapDns'
              port: 53
              network: '$FAKE_NET'
              netmask: '$FAKE_MASK'
              cache-size: 10000
            misc:
              log-level: warn
              connect-timeout: 30000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 15000
              max-session-count: 0
        """.trimIndent() + "\n"
    }
}
