package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.sqrt
import org.junit.Test

class BitmapPdfCompilerScaleTest {

    @Test
    fun `about halves the scale when actual size is four times the target`() {
        val result = nextScale(currentScale = 1f, targetBytes = 1_000_000, actualBytes = 4_000_000)

        // sqrt(1/4), aiming at 95% of the target.
        assertEquals(0.5f * sqrt(0.95f), result, 0.001f)
    }

    @Test
    fun `still shrinks a little when actual size equals the target, to land under it`() {
        val result = nextScale(currentScale = 1f, targetBytes = 1_000_000, actualBytes = 1_000_000)

        assertEquals(sqrt(0.95f), result, 0.001f)
    }

    @Test
    fun `compounds with the current scale rather than resetting it`() {
        val result = nextScale(currentScale = 0.5f, targetBytes = 1_000_000, actualBytes = 4_000_000)

        assertEquals(0.25f * sqrt(0.95f), result, 0.001f)
    }

    @Test
    fun `one step from far over the target lands under it even with bytes that don't shrink`() {
        // An OCR text layer (or encryption) adds bytes that don't scale with pixel
        // area; aiming exactly at the target would leave the file just over it, and
        // the next steps would only creep closer from above.
        val fixedOverhead = 35_000L
        val target = 1_048_576L
        fun sizeAt(scale: Float) = fixedOverhead + (2_900_000 * scale * scale).toLong()

        val scale = nextScale(currentScale = 1f, targetBytes = target, actualBytes = sizeAt(1f))

        assertTrue("landed at ${sizeAt(scale)} bytes", sizeAt(scale) <= target)
    }
}
