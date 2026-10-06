package com.papr.scanner.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TickThrottleTest {

    @Test
    fun `the first tick always plays`() {
        assertEquals(true, TickThrottle().tryTick(now = 0))
    }

    @Test
    fun `ticks closer together than the gap are dropped`() {
        val throttle = TickThrottle(minGapMillis = 40)

        val played = listOf(1_000L, 1_010L, 1_039L, 1_040L, 1_041L, 1_100L).map { throttle.tryTick(it) }

        assertEquals(listOf(true, false, false, true, false, true), played)
    }
}
