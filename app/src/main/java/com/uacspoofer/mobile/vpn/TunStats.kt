package com.uacspoofer.mobile.vpn

data class TunStats(
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
) {
    fun hasBidirectionalGrowthSince(previous: TunStats): Boolean =
        txBytes > previous.txBytes && rxBytes > previous.rxBytes

    fun hasUplinkGrowthSince(previous: TunStats): Boolean = txBytes > previous.txBytes

    fun hasDownlinkGrowthSince(previous: TunStats): Boolean = rxBytes > previous.rxBytes

    companion object {
        val ZERO = TunStats(0L, 0L, 0L, 0L)
    }
}
