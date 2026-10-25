package com.uacspoofer.mobile.engine.pow

import android.content.Context
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver

/**
 * Engine 3 only. Irancell stays on the H3 / IPv4 path.
 * Hamrah Aval (MCC 43211) gets the MCI outer ladder.
 */
internal data class PowRadioIdentity(
    val transport: String,
    val dataSubscriptionId: Int,
    val operatorCode: String,
    val carrierClass: String,
) {
    fun summary(): String =
        "$transport|sub=$dataSubscriptionId|op=$operatorCode|class=$carrierClass"

    /**
     * True when the default-data SIM / operator changed (Irancell ↔ MCI, etc.).
     * Same-SIM tower handoffs keep the same subId + MCC-MNC and return false.
     * Incomplete / unknown snapshots never count as a SIM change (avoids VPN
     * bring-up and subscription-listener false positives).
     */
    fun isSimOrOperatorChange(previous: PowRadioIdentity): Boolean {
        if (previous == this) return false
        if (transport == "unknown" || previous.transport == "unknown") return false

        val wasCell = previous.transport == "cellular"
        val isCell = transport == "cellular"
        if (wasCell != isCell) {
            // Wi-Fi ↔ cell is a hard radio change — but only when both sides are known.
            return true
        }
        if (!isCell) return false

        if (dataSubscriptionId >= 0 && previous.dataSubscriptionId >= 0 &&
            dataSubscriptionId != previous.dataSubscriptionId
        ) {
            return true
        }
        if (operatorCode.isNotBlank() && previous.operatorCode.isNotBlank() &&
            operatorCode != previous.operatorCode
        ) {
            return true
        }
        if (carrierClass != "unknown" && previous.carrierClass != "unknown" &&
            carrierClass != previous.carrierClass
        ) {
            return true
        }
        return false
    }

    /** Irancell ↔ MCI (or any known carrier class / MCC-MNC flip). */
    fun isConfirmedCarrierFlip(previous: PowRadioIdentity): Boolean {
        if (!isSimOrOperatorChange(previous)) return false
        val classFlip = carrierClass != "unknown" && previous.carrierClass != "unknown" &&
            carrierClass != previous.carrierClass
        val opFlip = operatorCode.isNotBlank() && previous.operatorCode.isNotBlank() &&
            operatorCode != previous.operatorCode
        val subFlip = dataSubscriptionId >= 0 && previous.dataSubscriptionId >= 0 &&
            dataSubscriptionId != previous.dataSubscriptionId
        return classFlip || opFlip || subFlip
    }
}

internal object PowCarrier {
    fun carrierClass(context: Context): String = runCatching {
        NetworkFingerprintResolver(context).capture().carrierClass
    }.getOrDefault("unknown")

    fun isMci(context: Context): Boolean = carrierClass(context) == "mci"

    fun radioIdentity(context: Context): PowRadioIdentity = runCatching {
        val fp = NetworkFingerprintResolver(context).capture()
        val op = runCatching {
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            val sub = fp.dataSubscriptionId
            val bound = if (sub >= 0) {
                runCatching { tm?.createForSubscriptionId(sub) }.getOrNull() ?: tm
            } else {
                tm
            }
            bound?.networkOperator.orEmpty()
        }.getOrDefault("")
        PowRadioIdentity(
            transport = fp.transport,
            dataSubscriptionId = fp.dataSubscriptionId,
            operatorCode = op.ifBlank { "" },
            carrierClass = fp.carrierClass,
        )
    }.getOrDefault(
        PowRadioIdentity(
            transport = "unknown",
            dataSubscriptionId = -1,
            operatorCode = "",
            carrierClass = "unknown",
        ),
    )
}
