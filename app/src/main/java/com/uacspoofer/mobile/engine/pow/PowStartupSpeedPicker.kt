package com.uacspoofer.mobile.engine.pow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs only inside connect(), before the device TUN is opened. No background jobs. */
internal object PowStartupSpeedPicker {
    const val MAX_EXTRA_MS = 60_000L
    const val ATTEMPT_MS = 30_000L
    const val MEMORY_TTL_MS = 6 * 60 * 60 * 1000L

    data class Route(val peer: String, val strategy: String)
    data class Sample(val bitsPerSecond: Long, val firstByteMs: Long)
    data class Choice(val route: Route, val sample: Sample?)

    fun candidates(initial: Route, peers: List<String>, strategies: List<String>, remembered: Route?): List<Route> {
        // Reuse discovered numeric peers only, in the already working IP family.
        val known = (listOf(initial.peer) + peers).distinct().filter {
            validPeer(it) && it.startsWith('[') == initial.peer.startsWith('[')
        }
        val routes = buildList {
            if (remembered != null && remembered.peer in known && remembered.strategy in strategies) add(remembered)
            known.firstOrNull { it != initial.peer }?.let { add(Route(it, initial.strategy)) }
            strategies.firstOrNull { it != initial.strategy }?.let { add(Route(initial.peer, it)) }
        }
        return routes.filter { it != initial }.distinct().take(2)
    }

    fun validPeer(peer: String): Boolean {
        val port = peer.substringAfterLast(':', "").toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        val host = peer.substringBeforeLast(':')
        if (host.startsWith('[') && host.endsWith(']')) {
            val ip = host.drop(1).dropLast(1)
            return ':' in ip && ip.all { it in "0123456789abcdefABCDEF:." }
        }
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    fun better(candidate: Sample, best: Sample): Boolean =
        candidate.bitsPerSecond > 0 && candidate.bitsPerSecond > best.bitsPerSecond * 110 / 100

    /** Preserve the live route when the download host is blocked; never mistake latency for bandwidth. */
    suspend fun select(
        initial: Route,
        candidates: List<Route>,
        now: () -> Long,
        activate: suspend (Route, Long) -> Boolean,
        measure: suspend () -> Sample?,
        report: (String) -> Unit,
    ): Choice {
        suspend fun tryActivate(route: Route, budget: Long): Boolean = try {
            activate(route, budget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            report("${route.peer} ${route.strategy}: connection trial failed (${error.javaClass.simpleName})")
            false
        }
        val baseline = measure() ?: return Choice(initial, null)
        report("${initial.peer} ${initial.strategy}: ${baseline.bitsPerSecond / 1000} kbit/s")
        var best = Choice(initial, baseline)
        var live: Route? = initial
        var lastWorking = initial
        val deadline = now() + MAX_EXTRA_MS
        for (route in candidates.distinct().filter { it != initial }.take(2)) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - now()
            if (remaining < 10_000) break
            // activate owns teardown; on failure the previous route needs restoring.
            live = null
            if (!tryActivate(route, minOf(ATTEMPT_MS, remaining))) break
            live = route
            lastWorking = route
            val sample = measure()
            if (sample != null) {
                report("${route.peer} ${route.strategy}: ${sample.bitsPerSecond / 1000} kbit/s")
                if (better(sample, best.sample!!)) best = Choice(route, sample)
            } else report("${route.peer} ${route.strategy}: download sample unavailable")
        }
        currentCoroutineContext().ensureActive()
        if (live == best.route) return best
        // Aether is a singleton: candidates are sequential, so restore the best recipe.
        // Keep a known connected recipe as fallback if the winning recipe cannot reconnect.
        val recoveryDeadline = now() + 2 * ATTEMPT_MS
        for (route in listOf(best.route, lastWorking, initial).distinct()) {
            currentCoroutineContext().ensureActive()
            val remaining = recoveryDeadline - now()
            if (remaining <= 0) break
            if (tryActivate(route, minOf(ATTEMPT_MS, remaining))) {
                // Reconnection can select a different Psiphon server: report the actual final rate.
                val finalSample = measure()
                report("selected ${route.peer} ${route.strategy}: ${finalSample?.bitsPerSecond?.div(1000) ?: "unmeasured"} kbit/s")
                return Choice(route, finalSample)
            }
        }
        error("Previously working MASQUE H2 paths could not reconnect")
    }
}
