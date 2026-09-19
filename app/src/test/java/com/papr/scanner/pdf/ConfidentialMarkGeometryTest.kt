package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidentialMarkGeometryTest {

    @Test
    fun `fits a wider-than-container image by full width, letterboxed vertically`() {
        val result = fittedImageBounds(containerWidth = 100f, containerHeight = 100f, imageWidth = 200f, imageHeight = 100f)

        assertEquals(FittedBounds(left = 0f, top = 25f, width = 100f, height = 50f), result)
    }

    @Test
    fun `fits a taller-than-container image by full height, letterboxed horizontally`() {
        val result = fittedImageBounds(containerWidth = 100f, containerHeight = 100f, imageWidth = 100f, imageHeight = 200f)

        assertEquals(FittedBounds(left = 25f, top = 0f, width = 50f, height = 100f), result)
    }

    @Test
    fun `fills the container exactly when aspect ratios match`() {
        val result = fittedImageBounds(containerWidth = 100f, containerHeight = 50f, imageWidth = 200f, imageHeight = 100f)

        assertEquals(FittedBounds(left = 0f, top = 0f, width = 100f, height = 50f), result)
    }

    @Test
    fun `crop mode fills the container fully and overflows the larger dimension`() {
        val result = fittedImageBounds(
            containerWidth = 100f,
            containerHeight = 100f,
            imageWidth = 200f,
            imageHeight = 100f,
            crop = true,
        )

        assertEquals(FittedBounds(left = -50f, top = 0f, width = 200f, height = 100f), result)
    }
}
