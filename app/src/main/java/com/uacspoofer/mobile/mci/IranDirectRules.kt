package com.uacspoofer.mobile.mci

/**
 * Iranian / private destinations that leave the phone directly (faster; many refuse foreign
 * exits). Uses geosite/geoip tags from the bundled Iran-v2ray-rules style dat files
 * (see [GeoAssets]); call [GeoAssets.ensure] before starting Xray.
 *
 * Banks (bmi.ir, …) are under geosite:category-bank-ir. We also keep domain:ir and an
 * explicit bmi.ir entry so DNS + routing never miss Iranian bank hosts.
 */
internal object IranDirectRules {
    val DOMAINS = listOf(
        "geosite:category-ir",
        "geosite:category-bank-ir",
        "domain:ir",
        "regexp:\\.ir$",
        "domain:bmi.ir",
        "full:www.bmi.ir",
    )

    val PRIVATE_IPS = listOf(
        "geoip:private",
    )

    val IR_IPS = listOf(
        "geoip:ir",
    )

    private fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
    fun domainsJson(): String = DOMAINS.joinToString(",") { "\"${q(it)}\"" }
    fun ipsJson(): String = PRIVATE_IPS.joinToString(",") { "\"$it\"" }
    fun irIpsJson(): String = IR_IPS.joinToString(",") { "\"$it\"" }
    fun allDirectIpsJson(): String =
        (PRIVATE_IPS + IR_IPS).joinToString(",") { "\"$it\"" }
}
