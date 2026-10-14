package com.uacspoofer.mobile.mci

/**
 * Iranian destinations that should leave the phone directly (faster, and many refuse foreign
 * exits). The bundled core has no geosite/geoip files, so this is a compact built-in list.
 */
internal object IranDirectRules {
    val DOMAINS = listOf(
        "regexp:\\.ir$", "domain:ir",
        "domain:aparat.com", "domain:digikala.com", "domain:dkstatics-public.digikala.com",
        "domain:snapp.taxi", "domain:snapp.ir", "domain:snappfood.ir", "domain:divar.ir",
        "domain:shaparak.ir", "domain:filimo.com", "domain:namava.ir", "domain:telewebion.com",
        "domain:varzesh3.com", "domain:tapsi.ir", "domain:torob.com", "domain:eitaa.com",
        "domain:rubika.ir", "domain:bale.ai", "domain:soroush-app.ir", "domain:arvancloud.ir",
        "domain:arvancdn.ir", "domain:cafebazaar.ir", "domain:myket.ir", "domain:emalls.ir",
        "domain:sheypoor.com", "domain:alibaba.ir", "domain:snapptrip.com", "domain:zoomit.ir",
        "domain:khabaronline.ir", "domain:tasnimnews.com", "domain:isna.ir", "domain:irna.ir",
        "domain:mehrnews.com", "domain:asriran.com", "domain:tamasha.com", "domain:digistyle.com",
        "domain:okala.com", "domain:basalam.com", "domain:jabama.com", "domain:sapp.ir",
    )
    val PRIVATE_IPS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10",
    )
    private fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
    fun domainsJson(): String = DOMAINS.joinToString(",") { "\"${q(it)}\"" }
    fun ipsJson(): String = PRIVATE_IPS.joinToString(",") { "\"$it\"" }
}