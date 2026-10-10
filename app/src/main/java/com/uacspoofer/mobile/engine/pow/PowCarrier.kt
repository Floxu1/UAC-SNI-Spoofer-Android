package com.uacspoofer.mobile.engine.pow

import android.content.Context
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver

/**
 * Engine 3 only. Irancell stays on the H3 / IPv4 path.
 * Hamrah Aval (MCC 43211) gets the MCI outer ladder.
 */
internal object PowCarrier {
    fun carrierClass(context: Context): String = runCatching {
        NetworkFingerprintResolver(context).capture().carrierClass
    }.getOrDefault("unknown")

    fun isMci(context: Context): Boolean = carrierClass(context) == "mci"
}
