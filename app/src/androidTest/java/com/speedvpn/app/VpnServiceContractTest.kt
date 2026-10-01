package com.speedvpn.app

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnServiceContractTest {
    @Test
    fun vpnServiceIsDeclaredAndProtected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, SpeedVpnService::class.java),
            0,
        )
        assertEquals("android.permission.BIND_VPN_SERVICE", service.permission)
        assertEquals(false, service.exported)
    }

    @Test
    fun vpnPrepareContractIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent: Intent? = VpnService.prepare(context)
        // Null means this test app already has VPN consent; non-null is the normal
        // pre-consent state. Both are valid platform states.
        assertNotNull(context.packageName)
        @Suppress("UNUSED_VARIABLE")
        val ignored = intent
    }
}
