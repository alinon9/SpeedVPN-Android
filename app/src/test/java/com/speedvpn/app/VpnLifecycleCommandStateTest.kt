package com.speedvpn.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecycleCommandStateTest {
    @Test
    fun tenStopStartCyclesPreserveStartRequestedDuringTeardown() {
        val state = VpnLifecycleCommandState()
        repeat(10) {
            state.requestStop()
            assertTrue(state.shouldDeferConnect())
            state.requestStart()
            assertTrue(state.shouldDeferConnect())
            assertTrue(state.finishStop())
            assertFalse(state.shouldDeferConnect())
            assertFalse(state.finishStop())
        }
    }

    @Test
    fun cleanStopDoesNotRequestReconnect() {
        val state = VpnLifecycleCommandState()
        state.requestStop()
        assertFalse(state.finishStop())
        assertFalse(state.shouldDeferConnect())
    }

    @Test
    fun settingsRestartPreservesQueuedReconnect() {
        val state = VpnLifecycleCommandState()
        state.requestStop(restartAfterStop = true)
        assertTrue(state.shouldDeferConnect())
        assertTrue(state.finishStop())
    }
}
