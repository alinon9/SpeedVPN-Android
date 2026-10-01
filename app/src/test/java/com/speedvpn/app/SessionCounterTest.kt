package com.speedvpn.app

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionCounterTest {
    @Test
    fun staleGenerationWriteCannotEnterNewGeneration() {
        val state = AtomicReference(SessionCounter(2L, 100L))
        state.updateAndGet { current ->
            if (current.generation == 3L) SessionCounter(3L, current.bytes + 50L) else current
        }
        assertEquals(SessionCounter(2L, 100L), state.get())
    }

    @Test
    fun resetChangesOnlyMatchingGeneration() {
        val state = AtomicReference(SessionCounter(7L, 500L))
        state.updateAndGet { current ->
            if (current.generation == 7L) SessionCounter(7L, 0L) else current
        }
        assertEquals(SessionCounter(7L, 0L), state.get())
    }
}
