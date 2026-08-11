package com.uacspoofer.mobile.mci

class MciRouteSelector(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val failedUntil = mutableMapOf<String, Long>()

    @Synchronized
    fun orderedEdges(edges: List<MciEdge> = MciConfig.EDGES): List<MciEdge> {
        val now = clockMs()
        val (healthy, coolingDown) = edges.partition {
            (failedUntil[it.address] ?: 0L) <= now
        }
        return healthy + coolingDown
    }

    @Synchronized
    fun recordFailure(edge: MciEdge) {
        failedUntil[edge.address] = clockMs() + MciConfig.EDGE_FAILURE_COOLDOWN_MS
    }

    @Synchronized
    fun recordSuccess(edge: MciEdge) {
        failedUntil.remove(edge.address)
    }
}
