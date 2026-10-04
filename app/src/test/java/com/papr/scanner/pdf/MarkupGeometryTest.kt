package com.papr.scanner.pdf

import com.papr.scanner.domain.model.InkStroke
import com.papr.scanner.domain.model.InkTool
import com.papr.scanner.domain.model.NormalizedPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupGeometryTest {

    // A horizontal line across the middle of a 1000x2000 page, 10px thick (0.01 of the 1000px shorter side).
    private val line = InkStroke(
        tool = InkTool.PEN,
        color = 0,
        width = 0.01f,
        points = listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.9f, 0.5f)),
    )

    @Test
    fun `a touch on the line hits it`() {
        assertTrue(isOnInkStroke(line, xPx = 500f, yPx = 1000f, widthPx = 1000, heightPx = 2000, slopPx = 0f))
    }

    @Test
    fun `a touch within half the line's width plus the slop hits it`() {
        assertTrue(isOnInkStroke(line, xPx = 500f, yPx = 1000f + 5f + 20f, widthPx = 1000, heightPx = 2000, slopPx = 20f))
    }

    @Test
    fun `a touch just beyond the slop misses`() {
        assertFalse(isOnInkStroke(line, xPx = 500f, yPx = 1000f + 5f + 21f, widthPx = 1000, heightPx = 2000, slopPx = 20f))
    }

    @Test
    fun `distance is measured in pixels, not stretched by the page's shape`() {
        // 25px to the side of the line's end: a normalized-space test would see 0.025 here
        // but only 0.0125 vertically, treating the two very differently on a tall page.
        assertFalse(isOnInkStroke(line, xPx = 900f + 26f, yPx = 1000f, widthPx = 1000, heightPx = 2000, slopPx = 20f))
        assertTrue(isOnInkStroke(line, xPx = 900f + 24f, yPx = 1000f, widthPx = 1000, heightPx = 2000, slopPx = 20f))
    }
}
