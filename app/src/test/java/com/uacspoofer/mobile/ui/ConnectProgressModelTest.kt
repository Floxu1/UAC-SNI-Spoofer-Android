package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.tor.TorFlowSnapshot
import com.uacspoofer.mobile.engine.tor.TorFlowStage
import com.uacspoofer.mobile.engine.tor.TorFlowStageId
import com.uacspoofer.mobile.engine.tor.TorFlowState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectProgressModelTest {
    private fun snap(vararg s: Pair<TorFlowStageId, TorFlowState>): TorFlowSnapshot {
        val base = TorFlowSnapshot()
        return base.copy(stages = base.stages + s.associate { (id, st) -> id to TorFlowStage(id, st) })
    }

    @Test fun combineRules() {
        assertEquals(MiniStepState.RUNNING, ConnectProgressModel.combine(listOf(TorFlowState.DONE, TorFlowState.RUNNING)))
        assertEquals(MiniStepState.FAILED, ConnectProgressModel.combine(listOf(TorFlowState.DONE, TorFlowState.FAILED)))
        assertEquals(MiniStepState.SKIPPED, ConnectProgressModel.combine(listOf(TorFlowState.SKIPPED)))
        assertEquals(MiniStepState.DONE, ConnectProgressModel.combine(listOf(TorFlowState.DONE, TorFlowState.SKIPPED)))
        assertEquals(MiniStepState.PENDING, ConnectProgressModel.combine(listOf(TorFlowState.PENDING)))
    }

    @Test fun torMidBootstrap() {
        val steps = ConnectProgressModel.torSteps(
            snap(
                TorFlowStageId.ARCH to TorFlowState.DONE, TorFlowStageId.POOL to TorFlowState.DONE,
                TorFlowStageId.SCAN to TorFlowState.DONE, TorFlowStageId.BATCH to TorFlowState.DONE,
                TorFlowStageId.FRAGMENT to TorFlowState.SKIPPED, TorFlowStageId.PT to TorFlowState.DONE,
                TorFlowStageId.HANDSHAKE to TorFlowState.DONE, TorFlowStageId.DIRECTORY to TorFlowState.RUNNING,
                TorFlowStageId.BOOTSTRAP to TorFlowState.RUNNING,
            ),
        )
        assertEquals(
            listOf(MiniStepState.DONE, MiniStepState.DONE, MiniStepState.RUNNING, MiniStepState.PENDING, MiniStepState.PENDING),
            steps.map { it.state },
        )
        assertEquals(2, ConnectProgressModel.focusIndex(steps))
    }

    @Test fun torFailurePinned() {
        val steps = ConnectProgressModel.torSteps(snap(TorFlowStageId.ARCH to TorFlowState.DONE, TorFlowStageId.PT to TorFlowState.FAILED))
        assertEquals(MiniStepState.FAILED, steps[1].state)
        assertEquals(1, ConnectProgressModel.focusIndex(steps))
    }

    @Test fun powMapping() {
        assertEquals(
            listOf(MiniStepState.DONE, MiniStepState.RUNNING, MiniStepState.PENDING, MiniStepState.PENDING),
            ConnectProgressModel.powSteps(PowPhase.OUTER, PowPhase.OUTER).map { it.state },
        )
        assertEquals(
            listOf(MiniStepState.DONE, MiniStepState.DONE, MiniStepState.FAILED, MiniStepState.PENDING),
            ConnectProgressModel.powSteps(PowPhase.FAILED, PowPhase.INNER).map { it.state },
        )
        assertEquals(
            List(4) { MiniStepState.DONE },
            ConnectProgressModel.powSteps(PowPhase.CONNECTED, PowPhase.CONNECTED).map { it.state },
        )
    }

    @Test fun elapsedFormat() {
        assertEquals("0:07", ConnectProgressModel.formatElapsed(7_400))
        assertEquals("1:05", ConnectProgressModel.formatElapsed(65_000))
    }
}