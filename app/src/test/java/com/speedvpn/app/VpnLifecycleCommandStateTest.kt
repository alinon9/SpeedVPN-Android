package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecycleCommandStateTest {
    @Test
    fun tenStopStartCyclesPreserveStartRequestedDuringTeardown() {
        val state = VpnLifecycleCommandState()
        repeat(10) {
            val token = state.requestStop()
            assertTrue(state.shouldDeferConnect())
            state.requestStart()
            assertTrue(state.shouldDeferConnect())
            assertTrue(state.finishStop(token))
            assertFalse(state.shouldDeferConnect())
            assertFalse(state.finishStop(token))
        }
    }

    @Test
    fun staleServiceCannotReleaseAnotherStopsGate() {
        val state = VpnLifecycleCommandState()
        val oldToken = state.requestStop()
        val currentToken = state.requestStop()
        state.requestStart()

        assertFalse(state.finishStop(oldToken))
        assertTrue(state.shouldDeferConnect())
        assertTrue(state.finishStop(currentToken))
        assertFalse(state.shouldDeferConnect())
    }

    @Test
    fun missingStaleTokenCannotReleasePendingStop() {
        val state = VpnLifecycleCommandState()
        val activeToken = state.requestStop()
        assertFalse(state.finishStop(null))
        assertTrue(state.shouldDeferConnect())
        assertFalse(state.finishStop(activeToken))
        assertFalse(state.shouldDeferConnect())
    }

    @Test
    fun cleanStopDoesNotRequestReconnect() {
        val state = VpnLifecycleCommandState()
        val token = state.requestStop()
        assertFalse(state.finishStop(token))
        assertFalse(state.shouldDeferConnect())
    }

    @Test
    fun settingsRestartPreservesQueuedReconnect() {
        val state = VpnLifecycleCommandState()
        val token = state.requestStop(restartAfterStop = true)
        assertTrue(state.shouldDeferConnect())
        assertTrue(state.finishStop(token))
    }

    @Test
    fun destroyReusesExistingTokenInsteadOfAddingDuplicateGate() {
        val state = VpnLifecycleCommandState()
        val original = state.requestStop()
        val reused = state.beginTeardown(original)
        assertEquals(original, reused)
        assertFalse(state.finishStop(original))
        assertFalse(state.shouldDeferConnect())
    }
}
