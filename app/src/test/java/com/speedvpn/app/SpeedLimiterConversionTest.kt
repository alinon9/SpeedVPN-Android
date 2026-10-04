package com.speedvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedLimiterConversionTest {
    @Test
    fun convertsKilobitsToBytesPerSecond() {
        val bucket = TokenBucket()

        bucket.setRate(10L * 125L)
        assertEquals(10L * 125L, bucket.bytesPerSec)

        bucket.setRate(800L * 125L)
        assertEquals(100_000L, bucket.bytesPerSec)

        bucket.setRate(8_000L * 125L)
        assertEquals(1_000_000L, bucket.bytesPerSec)

        bucket.setRate(40_000L * 125L)
        assertEquals(5_000_000L, bucket.bytesPerSec)

        bucket.setRate(800_000L * 125L)
        assertEquals(100_000_000L, bucket.bytesPerSec)
    }

    @Test
    fun zeroRateMeansUnlimited() {
        val bucket = TokenBucket()
        bucket.setRate(0L)
        assertEquals(0L, bucket.bytesPerSec)
    }
}
