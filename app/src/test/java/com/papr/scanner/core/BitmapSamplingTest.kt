package com.papr.scanner.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapSamplingTest {

    @Test
    fun `no sampling at or under the cap`() {
        assertEquals(1, sampleSizeFor(3000, 3000))
        assertEquals(1, sampleSizeFor(1200, 3000))
    }

    @Test
    fun `just under twice the cap still decodes full size`() {
        assertEquals(1, sampleSizeFor(5999, 3000))
    }

    @Test
    fun `twice the cap halves`() {
        assertEquals(2, sampleSizeFor(6000, 3000))
    }

    @Test
    fun `rounds down to a power of two like the decoder does`() {
        assertEquals(2, sampleSizeFor(9000, 3000))
        assertEquals(4, sampleSizeFor(12000, 3000))
    }
}
