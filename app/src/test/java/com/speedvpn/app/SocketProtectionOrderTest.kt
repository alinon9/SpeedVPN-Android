package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketProtectionOrderTest {
    @Test
    fun protectRunsBeforeNetworkBinding() {
        val events = mutableListOf<String>()
        val socket = Any()
        val result = protectSocketBeforeNetworkBinding(
            socket = socket,
            protect = { events += "protect"; it === socket },
            bindToNetwork = { events += "bind" },
        )
        assertTrue(result)
        assertEquals(listOf("protect", "bind"), events)
    }

    @Test
    fun failedProtectNeverBindsSocketToNetwork() {
        val events = mutableListOf<String>()
        val result = protectSocketBeforeNetworkBinding(
            socket = Any(),
            protect = { events += "protect"; false },
            bindToNetwork = { events += "bind" },
        )
        assertFalse(result)
        assertEquals(listOf("protect"), events)
    }
}
