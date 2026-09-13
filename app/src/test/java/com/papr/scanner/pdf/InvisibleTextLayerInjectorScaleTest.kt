package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class InvisibleTextLayerInjectorScaleTest {

    @Test
    fun `looks up known Helvetica character widths`() {
        assertEquals(944, helveticaWidth('W'.code))
        assertEquals(222, helveticaWidth('i'.code))
        assertEquals(278, helveticaWidth(' '.code))
    }

    @Test
    fun `falls back to the average width outside the mapped ASCII range`() {
        assertEquals(526, helveticaWidth('é'.code))
    }

    @Test
    fun `scales up when the detected box is wider than Helvetica's natural width`() {
        // "ii" in Helvetica at size 100 is naturally 2 * 222 / 1000 * 100 = 44.4pt wide.
        val result = horizontalScalePercent(text = "ii", fontSize = 100f, targetWidth = 88.8f)
        assertEquals(200f, result, 0.5f)
    }

    @Test
    fun `returns 100 percent when the target width already matches the natural width`() {
        val naturalWidth = 222 / 1000f * 100f // "i" at size 100
        val result = horizontalScalePercent(text = "i", fontSize = 100f, targetWidth = naturalWidth)
        assertEquals(100f, result, 0.5f)
    }

    @Test
    fun `clamps an extreme wide target instead of producing unreadable text`() {
        val result = horizontalScalePercent(text = "i", fontSize = 100f, targetWidth = 10000f)
        assertEquals(400f, result, 0.01f)
    }

    @Test
    fun `clamps an extreme narrow target instead of collapsing to nothing`() {
        val result = horizontalScalePercent(text = "i", fontSize = 100f, targetWidth = 0.01f)
        assertEquals(25f, result, 0.01f)
    }
}
