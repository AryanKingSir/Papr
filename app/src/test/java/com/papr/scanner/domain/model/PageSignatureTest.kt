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
    fun `rotating clockwise on a square page keeps the width and turns a centered signature in place`() {
        val original = PageSignature("s.png", x = 0.4f, y = 0.45f, width = 0.2f, aspectRatio = 0.5f)

        val result = original.rotatedClockwise(newPageAspect = 1f)

        assertEquals(0.2f, result.width, 0.0001f)
        assertEquals(0.5f, result.x + result.width / 2f, 0.0001f)
        assertEquals(0.5f, result.y + result.heightFraction(1f) / 2f, 0.0001f)
    }

    @Test
    fun `rotating clockwise moves a bottom-right signature to the bottom-left`() {
        val pageAspect = 0.75f
        val original = signature(x = 0.7f, width = 0.3f).let { it.copy(y = 1f - it.heightFraction(pageAspect)) }

        val result = original.rotatedClockwise(newPageAspect = 1f / pageAspect)

        assertEquals(0f, result.x, 0.0001f)
        assertEquals(1f, result.y + result.heightFraction(1f / pageAspect), 0.0001f)
    }

    @Test
    fun `rotating onto a differently shaped page keeps the signature's size on the page`() {
        // A 1600x2200 page turns into a 2200x1600 one: 0.5 of 1600px (800px) is 800/2200 of the new width.
        val original = signature(x = 0.2f, y = 0.4f, width = 0.5f)

        val result = original.rotatedClockwise(newPageAspect = 2200f / 1600f)

        assertEquals(800f / 2200f, result.width, 0.0001f)
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

    @Test
    fun `four rotations on a non-square page return to the start`() {
        // The placement that drifted on-device: a large signature near the bottom of a 1600x2200 page.
        val original = PageSignature("s.png", x = 0.208f, y = 0.7946f, width = 0.498f, aspectRatio = 0.4663f)
        val portrait = 1600f / 2200f
        val landscape = 2200f / 1600f

        val result = original.rotatedClockwise(landscape).rotatedClockwise(portrait)
            .rotatedClockwise(landscape).rotatedClockwise(portrait)

        assertEquals(original.x, result.x, 0.0001f)
        assertEquals(original.y, result.y, 0.0001f)
        assertEquals(original.width, result.width, 0.0001f)
    }
}
