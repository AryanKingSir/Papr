package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {

    @Test
    fun `an image matching the page fills it exactly`() {
        val placement = fitPlacement(1000, 1400, 1000, 1400)

        assertEquals(1f, placement.scale, 0.0001f)
        assertEquals(0f, placement.offsetX, 0.0001f)
        assertEquals(0f, placement.offsetY, 0.0001f)
    }

    @Test
    fun `a smaller image of the same shape is scaled up to fill the page`() {
        val placement = fitPlacement(1000, 1400, 800, 1120)

        assertEquals(1.25f, placement.scale, 0.0001f)
        assertEquals(0f, placement.offsetX, 0.001f)
        assertEquals(0f, placement.offsetY, 0.001f)
    }

    @Test
    fun `a narrower image is centered horizontally`() {
        val placement = fitPlacement(1000, 1000, 500, 1000)

        assertEquals(1f, placement.scale, 0.0001f)
        assertEquals(250f, placement.offsetX, 0.001f)
        assertEquals(0f, placement.offsetY, 0.001f)
    }

    @Test
    fun `a larger image is scaled down, never cropped`() {
        val placement = fitPlacement(1000, 1000, 2000, 1000)

        assertEquals(0.5f, placement.scale, 0.0001f)
        assertEquals(0f, placement.offsetX, 0.001f)
        assertEquals(250f, placement.offsetY, 0.001f)
    }

    @Test
    fun `maps bitmap pixels to bottom-up PDF points`() {
        val placement = PagePlacement(pageWidthPx = 1000, pageHeightPx = 1000, offsetX = 100f, offsetY = 50f, scale = 2f)

        assertEquals((100f + 10f * 2f) * POINTS_PER_PIXEL, placement.toPdfX(10f), 0.001f)
        assertEquals((1000f - (50f + 10f * 2f)) * POINTS_PER_PIXEL, placement.toPdfY(10f), 0.001f)
        assertEquals(10f * 2f * POINTS_PER_PIXEL, placement.toPdfLength(10f), 0.001f)
    }

    @Test
    fun `scaling keeps a page's shape`() {
        assertEquals(600 to 825, scaledSize(1200, 1650, scale = 0.5f, minDimension = 300))
    }

    @Test
    fun `the minimum-size floor scales both sides together instead of squashing the page`() {
        val (width, height) = scaledSize(1200, 1650, scale = 0.1f, minDimension = 300)

        assertEquals(300, width)
        assertEquals(413, height)
    }

    @Test
    fun `never scales a page up`() {
        assertEquals(200 to 250, scaledSize(200, 250, scale = 0.5f, minDimension = 300))
    }
}
