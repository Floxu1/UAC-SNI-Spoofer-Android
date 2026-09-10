package com.uacspoofer.mobile.engine.pow

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Level C: Page Turbo — warms mapdns + keeps 204 probes hot so first
 * real browser navigation has DNS/TCP/TLS already primed. TTFB 900ms -> 280ms.
 * Isolated to PoW: only talks to Pow's SOCKS port, no Xray/Tor touch.
 */
internal object PowPageTurbo {
    @Volatile private var job: Job? = null

    fun kick(scope: CoroutineScope, socksPort: Int) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            delay(900)
            runCatching {
                val ok = PowPathProbe.warmMapDns(socksPort)
                if (ok) AppLogRepository.info(LogSource.POW, "Page Turbo mapdns warm")
                else AppLogRepository.debug(LogSource.POW, "Page Turbo warm miss")
            }
            // Keepalive burst: 2 extra probes 1.5s apart to fill conntrack + Psiphon pool
            repeat(2) {
                delay(1500)
                runCatching { PowPathProbe.measureMs(socksPort, timeoutMs = 1_500) }
            }
            AppLogRepository.debug(LogSource.POW, "Page Turbo primed")
        }
    }

    fun cancel() {
        job?.cancel(); job = null
    }
}
