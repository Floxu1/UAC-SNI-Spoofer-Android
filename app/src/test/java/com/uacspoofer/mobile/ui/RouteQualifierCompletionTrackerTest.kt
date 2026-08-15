package com.uacspoofer.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteQualifierCompletionTrackerTest {
    @Test
    fun claimsEachLogicalCandidateOnlyOnceAcrossRetries() {
        val tracker = RouteQualifierCompletionTracker(listOf("already-complete"))

        assertEquals(
            setOf("resolver-a", "resolver-b"),
            tracker.claim(listOf("resolver-a", "resolver-a", "resolver-b")),
        )
        assertTrue(tracker.claim(listOf("resolver-a", "resolver-b")).isEmpty())
        assertEquals(3, tracker.count)
    }

    @Test
    fun claimsAnEquivalentResolverGroupAtomically() {
        val tracker = RouteQualifierCompletionTracker()
        val logicalGroup = listOf(
            "edge-cloudflare",
            "edge-google",
            "edge-quad9",
            "edge-opendns",
            "edge-nextdns",
        )

        assertEquals(logicalGroup.toSet(), tracker.claim(logicalGroup))
        assertEquals(logicalGroup.size, tracker.count)
    }

    @Test
    fun recommendationUsesLatestStageWithAnAcceptedResult() {
        val qualifierOnly = routeRow(
            id = "qualifier-only",
            edge = "104.18.1.1:443",
            tournamentScore = 900,
            observations = listOf(routeObservation(RouteTournamentStage.QUALIFIER, accepted = true)),
        )
        val verified = routeRow(
            id = "verified",
            edge = "172.66.0.1:443",
            tournamentScore = 200,
            observations = listOf(
                routeObservation(RouteTournamentStage.QUALIFIER, accepted = true),
                routeObservation(RouteTournamentStage.VERIFICATION, accepted = true),
            ),
        )

        val recommended = recommendationRowsForStage(
            rows = listOf(qualifierOnly, verified),
            currentStage = RouteTournamentStage.VERIFICATION,
            stageCandidateIds = { stage ->
                when (stage) {
                    RouteTournamentStage.QUALIFIER -> listOf(qualifierOnly.candidateId, verified.candidateId)
                    RouteTournamentStage.VERIFICATION -> listOf(verified.candidateId)
                    else -> emptyList()
                }
            },
        )

        assertEquals(listOf("verified"), recommended.map(RouteSpeedRow::candidateId))
    }

    @Test
    fun recommendationFallsBackToTheLastSuccessfulStageWhileTheNextStageStarts() {
        val qualifier = routeRow(
            id = "qualifier",
            edge = "104.18.1.1:443",
            tournamentScore = 500,
            observations = listOf(routeObservation(RouteTournamentStage.QUALIFIER, accepted = true)),
        )

        val recommended = recommendationRowsForStage(
            rows = listOf(qualifier),
            currentStage = RouteTournamentStage.VERIFICATION,
            stageCandidateIds = { stage ->
                when (stage) {
                    RouteTournamentStage.QUALIFIER -> listOf(qualifier.candidateId)
                    RouteTournamentStage.VERIFICATION -> listOf(qualifier.candidateId)
                    else -> emptyList()
                }
            },
        )

        assertEquals(listOf("qualifier"), recommended.map(RouteSpeedRow::candidateId))
    }

    @Test
    fun completedTournamentOnlyRecommendsAcceptedChampionshipRows() {
        val stressOnly = routeRow(
            id = "stress-only",
            edge = "104.18.1.1:443",
            tournamentScore = 950,
            observations = listOf(routeObservation(RouteTournamentStage.STRESS, accepted = true)),
        )
        val finalist = routeRow(
            id = "finalist",
            edge = "172.66.0.1:443",
            tournamentScore = 300,
            observations = listOf(routeObservation(RouteTournamentStage.CHAMPIONSHIP, accepted = true)),
        )

        val recommended = recommendationRowsForStage(
            rows = listOf(stressOnly, finalist),
            currentStage = RouteTournamentStage.COMPLETE,
            stageCandidateIds = { stage ->
                if (stage == RouteTournamentStage.CHAMPIONSHIP) listOf(stressOnly.candidateId, finalist.candidateId)
                else emptyList()
            },
        )

        assertEquals(listOf("finalist"), recommended.map(RouteSpeedRow::candidateId))
    }

    @Test
    fun backupPrefersAnotherSubnetBeforeACloserRankedDuplicate() {
        val champion = routeRow("champion", "104.18.1.1:443", 900)
        val sameEndpoint = routeRow("same-endpoint", "104.18.1.1:443", 850)
        val sameSubnet = routeRow("same-subnet", "104.18.1.2:443", 800)
        val diverse = routeRow("diverse", "172.66.0.1:443", 700)

        val backup = preferredBackupRow(
            champion = champion,
            rankedRows = listOf(champion, sameEndpoint, sameSubnet, diverse),
        )

        assertEquals("diverse", backup?.candidateId)
    }

    @Test
    fun previousFinalRowsAreLimitedToTheCurrentPlan() {
        val valid = routeRow("valid", "104.18.1.1:443", 500)
        val stale = routeRow("stale", "172.66.0.1:443", 600)

        val restored = filterRestorableFinalRows(listOf(stale, valid), listOf(valid.candidateId))

        assertEquals(listOf("valid"), restored.map(RouteSpeedRow::candidateId))
    }

    private fun routeRow(
        id: String,
        edge: String,
        tournamentScore: Int,
        observations: List<RouteObservation> = emptyList(),
    ) = RouteSpeedRow(
        candidateId = id,
        label = id,
        route = edge,
        edgeKey = edge,
        resolverKey = "cloudflare",
        fragmentKey = "Fragment off",
        mtu = 1_280,
        tournamentScore = tournamentScore,
        successfulSamples = observations.count(RouteObservation::accepted),
        observations = observations,
    )

    private fun routeObservation(
        stage: RouteTournamentStage,
        accepted: Boolean,
    ) = RouteObservation(
        stage = stage,
        accepted = accepted,
        score = if (accepted) 80 else 0,
        latencyMs = if (accepted) 100L else null,
        dnsLatencyMs = if (accepted) 50L else null,
        payloadBytes = if (accepted) 1_024 else 0,
        throughputKbps = if (accepted) 1_000L else 0L,
        httpSucceeded = if (accepted) 2 else 0,
        httpAttempted = 2,
        dnsSucceeded = accepted,
        detail = if (accepted) "healthy" else "failed",
        failureFingerprint = if (accepted) "Healthy" else "Failed",
    )
}
