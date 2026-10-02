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

        val result = stroke.rotatedClockwise()

        assertEquals(listOf(NormalizedPoint(0.5f, 0.25f), NormalizedPoint(0.875f, 0.75f)), result.points)
    }

    @Test
    fun `four clockwise rotations restore the stroke`() {
        val stroke = ConfidentialMarkStroke(listOf(NormalizedPoint(0.25f, 0.5f), NormalizedPoint(0.75f, 0.125f)))

        val result = stroke.rotatedClockwise().rotatedClockwise().rotatedClockwise().rotatedClockwise()

        assertEquals(stroke, result)
    }
}
