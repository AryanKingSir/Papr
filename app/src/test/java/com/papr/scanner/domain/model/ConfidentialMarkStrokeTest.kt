package com.papr.scanner.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidentialMarkStrokeTest {

    @Test
    fun `encodes a single stroke with multiple points`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.15f, 0.22f))),
        )

        val result = encodeMarkStrokes(strokes)

        assertEquals("0.1,0.2;0.15,0.22", result)
    }

    @Test
    fun `encodes multiple strokes separated by a pipe`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f))),
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.5f, 0.6f))),
        )

        val result = encodeMarkStrokes(strokes)

        assertEquals("0.1,0.2|0.5,0.6", result)
    }

    @Test
    fun `encodes an empty list as an empty string`() {
        assertEquals("", encodeMarkStrokes(emptyList()))
    }

    @Test
    fun `decodes back to the original strokes`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.15f, 0.22f))),
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.5f, 0.6f))),
        )

        val result = decodeMarkStrokes(encodeMarkStrokes(strokes))

        assertEquals(strokes, result)
    }

    @Test
    fun `decodes null as an empty list`() {
        assertEquals(emptyList<ConfidentialMarkStroke>(), decodeMarkStrokes(null))
    }

    @Test
    fun `decodes a blank string as an empty list`() {
        assertEquals(emptyList<ConfidentialMarkStroke>(), decodeMarkStrokes(""))
    }

    @Test
    fun `rotating a stroke clockwise maps x,y to 1-y,x`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0.25f, 0.5f), NormalizedPoint(0.75f, 0.125f)))

        val result = stroke.rotatedClockwise(pageAspect = 1f)

        assertEquals(listOf(NormalizedPoint(0.5f, 0.25f), NormalizedPoint(0.875f, 0.75f)), result.points)
    }

    @Test
    fun `four clockwise rotations restore the stroke`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0.25f, 0.5f), NormalizedPoint(0.75f, 0.125f)))

        // A landscape page (4:3) turns portrait, landscape, portrait, landscape again.
        val result = stroke.rotatedClockwise(4f / 3f).rotatedClockwise(3f / 4f).rotatedClockwise(4f / 3f).rotatedClockwise(3f / 4f)

        assertEquals(stroke, result)
    }

    @Test
    fun `a mark keeps its physical thickness when a landscape page turns portrait`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.9f, 0.5f)))

        // 4% of a 3200px-wide page is 128px; the turned page is 2400px wide, so 128px is 5.33% of it.
        val result = stroke.rotatedClockwise(pageAspect = 4f / 3f)

        assertEquals(DEFAULT_CONFIDENTIAL_MARK_WIDTH * 4f / 3f, result.width!!, 0.0001f)
    }

    @Test
    fun `a mark's own width survives saving and loading`() {
        val strokes = listOf(
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.3f, 0.4f)), width = 0.0533f),
            ConfidentialMarkStroke(listOf(NormalizedPoint(0.5f, 0.6f), NormalizedPoint(0.7f, 0.8f))),
        )

        assertEquals(strokes, decodeMarkStrokes(encodeMarkStrokes(strokes)))
    }

    @Test
    fun `marks saved before widths existed load with the standard width`() {
        assertEquals(null, decodeMarkStrokes("0.1,0.2;0.3,0.4").single().width)
    }
}
