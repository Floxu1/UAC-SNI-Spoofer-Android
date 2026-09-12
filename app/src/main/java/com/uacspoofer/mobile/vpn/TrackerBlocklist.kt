package com.uacspoofer.mobile.vpn

/**
 * Compact tracker/ad suffix set used by the DNS sinkhole.
 * Matching is suffix-based (`ads.example.com` matches `example.com`).
 * Loaded once; the TUN loop never rebuilds this set.
 */
internal object TrackerBlocklist {
    val domains: Set<String> = hashSetOf(
        "2mdn.net",
        "ad.doubleclick.net",
        "adform.net",
        "adgrx.com",
        "adinplay.com",
        "adition.com",
        "admob.com",
        "adnxs.com",
        "adroll.com",
        "ads-twitter.com",
        "ads.linkedin.com",
        "ads.tiktok.com",
        "ads.yahoo.com",
        "adservice.google.com",
        "adsafeprotected.com",
        "adsrvr.org",
        "adsymptotic.com",
        "advertising.com",
        "agkn.com",
        "amazon-adsystem.com",
        "amplitude.com",
        "analytics.google.com",
        "analytics.tiktok.com",
        "analytics.twitter.com",
        "analytics.yahoo.com",
        "app-measurement.com",
        "appsflyer.com",
        "bat.bing.com",
        "bidswitch.net",
        "bluekai.com",
        "casalemedia.com",
        "chartbeat.com",
        "clarity.ms",
        "contextweb.com",
        "crash.163.com",
        "criteo.com",
        "crwdcntrl.net",
        "demdex.net",
        "doubleclick.net",
        "doubleverify.com",
        "exelator.com",
        "facebook.net",
        "flashtalking.com",
        "google-analytics.com",
        "googleadservices.com",
        "googlesyndication.com",
        "googletagmanager.com",
        "googletagservices.com",
        "hotjar.com",
        "imrworldwide.com",
        "krxd.net",
        "liadm.com",
        "mathtag.com",
        "mc.yandex.com",
        "mc.yandex.ru",
        "media.net",
        "mixpanel.com",
        "moatads.com",
        "mookie1.com",
        "nr-data.net",
        "omtrdc.net",
        "openx.net",
        "outbrain.com",
        "pagead2.googlesyndication.com",
        "partner.googleadservices.com",
        "pixel.facebook.com",
        "pubmatic.com",
        "quantserve.com",
        "rlcdn.com",
        "rubiconproject.com",
        "scorecardresearch.com",
        "segment.io",
        "serving-sys.com",
        "smartadserver.com",
        "snap.licdn.com",
        "taboola.com",
        "tapad.com",
        "tpc.googlesyndication.com",
        "tr.outbrain.com",
        "trafficshaper.dsp.rkdms.com",
        "turn.com",
        "yieldmo.com",
    )

    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        if (domains.contains(host)) return true
        var index = 0
        while (index < host.length) {
            if (host[index] == '.') {
                if (domains.contains(host.substring(index + 1))) return true
            }
            index++
        }
        return false
    }
}
