package com.uacspoofer.mobile.vpn

internal enum class RuntimeHealthAction {
    KEEP_CONNECTED,
    RECOVER,
}


internal class RuntimeHealthGuard(
    private val maxConsecutiveFailures: Int,
) {
    init {
        require(maxConsecutiveFailures > 0)
    }

    var consecutiveFailures: Int = 0
        private set

    fun recordHealthy() {
        consecutiveFailures = 0
    }

    fun recordFailure(coreRunning: Boolean): RuntimeHealthAction {
        if (!coreRunning) return RuntimeHealthAction.RECOVER
        consecutiveFailures += 1
        return if (consecutiveFailures >= maxConsecutiveFailures) {
            RuntimeHealthAction.RECOVER
        } else {
            RuntimeHealthAction.KEEP_CONNECTED
        }
    }
}
