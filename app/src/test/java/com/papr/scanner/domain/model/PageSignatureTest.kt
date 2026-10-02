package com.papr.scanner.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSignatureTest {

    private fun signature(x: Float = 0.1f, y: Float = 0.1f, width: Float = 0.3f, aspectRatio: Float = 0.5f) =
        PageSignature("s.png", x, y, width, aspectRatio)

    @Test
    fun `height fraction accounts for the page's own shape`() {
        // 0.3 of a 1000px-wide page is 300px wide and 150px tall; on a 2000px-tall page that's 0.075
        assertEquals(0.075f, signature().heightFraction(pageAspect = 0.5f), 0.0001f)
    }

    @Test
    fun `default placement sits in the bottom-right corner, inside the page`() {
        val result = defaultSignaturePlacement("s.png", aspectRatio = 0.5f, pageAspect = 0.75f)

        assertEquals(1f / 3f, result.width, 0.0001f)
        assertTrue(result.x > 0.5f && result.x + result.width < 1f)
        assertTrue(result.y > 0.5f && result.y + result.heightFraction(0.75f) < 1f)
    }

    @Test
    fun `clamping keeps the signature fully on the page`() {
        val result = signature(x = 0.9f, y = 0.98f).clampedTo(pageAspect = 0.75f)

        assertEquals(0.7f, result.x, 0.0001f)
        assertEquals(1f - result.heightFraction(0.75f), result.y, 0.0001f)
    }

    @Test
    fun `clamping enforces a minimum and maximum width`() {
        assertEquals(MIN_SIGNATURE_WIDTH, signature(width = 0.001f).clampedTo(0.75f).width, 0.0001f)
        assertEquals(1f, signature(x = 0f, width = 3f, aspectRatio = 0.1f).clampedTo(0.75f).width, 0.0001f)
    }

    @Test
    fun `clamping shrinks a signature too tall for the page`() {
        val result = signature(x = 0f, y = 0f, width = 1f, aspectRatio = 2f).clampedTo(pageAspect = 1f)

        assertEquals(1f, result.heightFraction(1f), 0.0001f)
        assertEquals(0.5f, result.width, 0.0001f)
    }

    @Test
    fun `rotating clockwise moves the center with the page and keeps the width`() {
        // On a square page, center (0.25, 0.25) rotates to (0.75, 0.25)
        val original = PageSignature("s.png", x = 0.15f, y = 0.2f, width = 0.2f, aspectRatio = 0.5f)

        val result = original.rotatedClockwise(newPageAspect = 1f)

        assertEquals(0.2f, result.width, 0.0001f)
        assertEquals(0.75f, result.x + result.width / 2f, 0.0001f)
        assertEquals(0.25f, result.y + result.heightFraction(1f) / 2f, 0.0001f)
    }

    @Test
    fun `rotating onto a differently shaped page keeps the signature on the page`() {
        val original = PageSignature("s.png", x = 0.6f, y = 0.85f, width = 0.33f, aspectRatio = 0.5f)

        val result = original.rotatedClockwise(newPageAspect = 4f / 3f)

        assertTrue(result.x >= 0f && result.x + result.width <= 1f)
        assertTrue(result.y >= 0f && result.y + result.heightFraction(4f / 3f) <= 1.0001f)
    }

    @Test
    fun `four rotations on a square page return to the start`() {
        val original = PageSignature("s.png", x = 0.1f, y = 0.6f, width = 0.3f, aspectRatio = 0.4f)

        val result = original.rotatedClockwise(1f).rotatedClockwise(1f).rotatedClockwise(1f).rotatedClockwise(1f)

        assertEquals(original.x, result.x, 0.0001f)
        assertEquals(original.y, result.y, 0.0001f)
    }
}
