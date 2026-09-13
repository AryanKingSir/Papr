package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapPdfCompilerScaleTest {

    @Test
    fun `halves the scale when actual size is four times the target`() {
        val result = nextScale(currentScale = 1f, targetBytes = 1_000_000, actualBytes = 4_000_000)

        assertEquals(0.5f, result, 0.001f)
    }

    @Test
    fun `returns the same scale when actual size already equals the target`() {
        val result = nextScale(currentScale = 1f, targetBytes = 1_000_000, actualBytes = 1_000_000)

        assertEquals(1f, result, 0.001f)
    }

    @Test
    fun `compounds with the current scale rather than resetting it`() {
        val result = nextScale(currentScale = 0.5f, targetBytes = 1_000_000, actualBytes = 4_000_000)

        assertEquals(0.25f, result, 0.001f)
    }
}
