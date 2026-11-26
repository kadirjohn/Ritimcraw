package com.ritim.ritimcraw.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyOverCapTest {
    @Test fun overCapIsDifferenceWhenAbove() = assertEquals(250L, overCapBytes(1250, 1000))

    @Test fun overCapIsZeroWhenBelowOrEqual() {
        assertEquals(0L, overCapBytes(999, 1000))
        assertEquals(0L, overCapBytes(1000, 1000))
        assertEquals(0L, overCapBytes(0, 0))
    }

    @Test fun overCapClampsNegativeInputsAndNeverOverflows() {
        assertEquals(0L, overCapBytes(-5, 100))
        assertEquals(100L, overCapBytes(100, -5))
        assertEquals(Long.MAX_VALUE, overCapBytes(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(0L, overCapBytes(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun overCapAgainstTheDefaultCap() {
        assertEquals(1L, overCapBytes(PROXY_CAP_DEFAULT_BYTES + 1, PROXY_CAP_DEFAULT_BYTES))
        assertEquals(0L, overCapBytes(PROXY_CAP_DEFAULT_BYTES, PROXY_CAP_DEFAULT_BYTES))
    }

    @Test fun usageWithinCapShowsNothing() {
        val u = ProxyCapUsage(totalBytes = 900, capBytes = 1000, protectedBytes = 500)
        assertEquals(0L, u.overCapBytes)
        assertFalse(u.isOverCap)
        assertEquals(0L, u.overCapBecauseProtectedBytes)
    }

    @Test fun usageLoweredCapBelowProtected() {
        val u = ProxyCapUsage(totalBytes = 1500, capBytes = 400, protectedBytes = 600)
        assertEquals(1100L, u.overCapBytes)
        assertTrue(u.isOverCap)
        assertEquals(600L, u.overCapBecauseProtectedBytes)
    }

    @Test fun protectedOverageIsBoundedByOverage() {
        val u = ProxyCapUsage(totalBytes = 1100, capBytes = 1000, protectedBytes = 600)
        assertEquals(100L, u.overCapBytes)
        assertEquals(100L, u.overCapBecauseProtectedBytes)
    }
}
