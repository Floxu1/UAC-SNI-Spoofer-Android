package com.uacspoofer.mobile.engine.pow

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sticky tunnel choice for "two tunnels at once".
 *
 * Psiphon's own TunnelPoolSize hands port forwards to its tunnels round-robin, and
 * the pooled tunnels are always different servers, so one service saw two exit IPs
 * and IP-bound CDN links (Instagram/Facebook video) stopped after a few seconds.
 * Here every connection of one service (Instagram with its CDNs, YouTube with
 * Google, ...) is pinned to one of the two independent Psiphon tunnels, so the
 * service always sees one exit IP, while different services spread over both.
 *
 * A pin only moves when its tunnel stops answering, and then it stays on the
 * survivor so the exit IP of that service changes at most once.
 */
internal object PowStickyRoute {
    const val TUNNEL_1 = 0
    const val TUNNEL_2 = 1
    private const val MAX_PINS = 4096
    private const val MAX_LOG_LINES = 40

    data class Choice(val tunnel: Int, val group: String, val fresh: Boolean, val moved: Boolean)

    /** Domains whose connections must share one exit IP. Matched by suffix. */
    private val SERVICE_GROUPS: Map<String, String> = listOf(
        "meta" to listOf(
            "instagram.com", "cdninstagram.com", "fbcdn.net", "facebook.com", "facebook.net",
            "fb.com", "fb.me", "fbsbx.com", "messenger.com", "m.me", "whatsapp.com",
            "whatsapp.net", "wa.me", "threads.net", "threads.com", "meta.com",
        ),
        // YouTube video URLs are signed for the IP that asked the Google API
        // (googleapis.com), so YouTube and Google must share one tunnel.
        "google" to listOf(
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "googlevideo.com", "youtube.com", "youtu.be", "ytimg.com", "ggpht.com",
            "youtube-nocookie.com", "gvt1.com", "gvt2.com", "gvt3.com", "android.com",
            "gmail.com", "googlesyndication.com", "doubleclick.net", "google-analytics.com",
            "googletagmanager.com", "app-measurement.com", "withgoogle.com",
        ),
        "telegram" to listOf(
            "telegram.org", "t.me", "telegram.me", "telesco.pe", "cdn-telegram.org", "tdesktop.com",
        ),
        "x" to listOf("x.com", "twitter.com", "twimg.com", "t.co"),
        "tiktok" to listOf(
            "tiktok.com", "tiktokcdn.com", "tiktokv.com", "tiktokcdn-us.com", "byteoversea.com",
            "ibytedtos.com", "ibyteimg.com", "muscdn.com", "musical.ly",
        ),
        "apple" to listOf("apple.com", "icloud.com", "mzstatic.com", "apple-cloudkit.com", "cdn-apple.com", "aaplimg.com"),
        "microsoft" to listOf(
            "microsoft.com", "live.com", "office.com", "office.net", "microsoftonline.com",
            "msftauth.net", "outlook.com", "skype.com", "msn.com", "bing.com",
        ),
        "netflix" to listOf("netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net"),
        "spotify" to listOf("spotify.com", "scdn.co", "spotifycdn.com"),
        "discord" to listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media"),
        "reddit" to listOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"),
        "snapchat" to listOf("snapchat.com", "sc-cdn.net"),
        "paypal" to listOf("paypal.com", "paypalobjects.com"),
    ).flatMap { (group, domains) -> domains.map { it to group } }.toMap()

    /** The two heaviest services start on different tunnels; the rest are hashed. */
    private val FIXED_TUNNEL = mapOf("meta" to TUNNEL_2, "google" to TUNNEL_1)

    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val pins = ConcurrentHashMap<String, Int>()
    private val logLines = AtomicInteger(0)
    @Volatile private var pinning = false

    /** True once tunnel 2 has been up in this session; until then nothing is pinned. */
    val active: Boolean get() = pinning

    fun groupOf(target: String): String {
        val host = target.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return "ip:?"
        if (host.contains(':') || IPV4.matches(host)) return "ip:$host"
        val labels = host.split('.').filter { it.isNotEmpty() }
        for (start in labels.indices) {
            val suffix = labels.subList(start, labels.size).joinToString(".")
            SERVICE_GROUPS[suffix]?.let { return it }
        }
        if (isGoogleCountryDomain(labels)) return "google"
        return registrable(labels)
    }

    private fun isGoogleCountryDomain(labels: List<String>): Boolean {
        val n = labels.size
        if (n >= 2 && labels[n - 2] == "google") return true
        return n >= 3 && labels[n - 3] == "google" && labels[n - 2] in setOf("co", "com")
    }

    private fun registrable(labels: List<String>): String {
        val n = labels.size
        if (n <= 2) return labels.joinToString(".")
        val take = if (labels[n - 1].length == 2 && labels[n - 2] in SECOND_LEVEL) 3 else 2
        return labels.subList(n - take, n).joinToString(".")
    }

    /** Stable FNV-1a hash: the same group lands on the same tunnel in every session. */
    fun preferredFor(group: String): Int {
        FIXED_TUNNEL[group]?.let { return it }
        var hash = 0x811c9dc5.toInt()
        for (ch in group) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return Math.floorMod(hash, 2)
    }

    fun choose(target: String, tunnel2Up: Boolean): Choice {
        val group = groupOf(target)
        if (tunnel2Up) pinning = true
        if (!pinning) return Choice(TUNNEL_1, group, fresh = false, moved = false)
        val pinned = pins[group]
        if (pinned != null) {
            if (pinned == TUNNEL_2 && !tunnel2Up) {
                pins[group] = TUNNEL_1
                return Choice(TUNNEL_1, group, fresh = false, moved = true)
            }
            return Choice(pinned, group, fresh = false, moved = false)
        }
        val pick = if (tunnel2Up) preferredFor(group) else TUNNEL_1
        if (pins.size >= MAX_PINS) return Choice(pick, group, fresh = false, moved = false)
        val prior = pins.putIfAbsent(group, pick)
        return if (prior == null) {
            Choice(pick, group, fresh = true, moved = false)
        } else {
            Choice(prior, group, fresh = false, moved = false)
        }
    }

    /** The pinned tunnel did not answer locally; keep the group on the other one from now on. */
    fun moveTo(group: String, tunnel: Int) {
        if (pinning) pins[group] = tunnel
    }

    fun shouldLog(): Boolean = logLines.incrementAndGet() <= MAX_LOG_LINES

    fun pinnedCount(): Int = pins.size

    fun reset() {
        pins.clear()
        pinning = false
        logLines.set(0)
    }
}
