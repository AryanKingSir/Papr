package com.papr.scanner.presentation.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureCropTest {

    @Test
    fun `no strokes means nothing to crop`() {
        assertNull(signatureCropRect(emptyList(), padding = 4f, canvasWidth = 100, canvasHeight = 100))
    }

    @Test
    fun `crops to the strokes plus padding`() {
        val strokes = listOf(listOf(Offset(20f, 30f), Offset(60f, 40f)))

        val result = signatureCropRect(strokes, padding = 5f, canvasWidth = 200, canvasHeight = 100)

        assertEquals(IntRect(15, 25, 65, 45), result)
    }

    @Test
    fun `covers every stroke, not just the first`() {
        val strokes = listOf(listOf(Offset(20f, 30f), Offset(30f, 35f)), listOf(Offset(80f, 10f), Offset(90f, 50f)))

        val result = signatureCropRect(strokes, padding = 0f, canvasWidth = 200, canvasHeight = 100)

        assertEquals(IntRect(20, 10, 90, 50), result)
    }

    @Test
    fun `padding never extends past the canvas`() {
        val strokes = listOf(listOf(Offset(1f, 1f), Offset(99f, 99f)))

        val result = signatureCropRect(strokes, padding = 10f, canvasWidth = 100, canvasHeight = 100)

        assertEquals(IntRect(0, 0, 100, 100), result)
    }

    @Test
    fun `a signature drawn on a wider pad shrinks to fit a narrower one, keeping its shape`() {
        val strokes = listOf(listOf(Offset(900f, 100f), Offset(1100f, 200f)))

        val fitted = strokesFittedToPad(strokes, from = IntSize(1200, 400), to = IntSize(600, 400))

        assertEquals(listOf(listOf(Offset(450f, 50f), Offset(550f, 100f))), fitted)
    }

    @Test
    fun `a signature on a pad that got wider stays as drawn`() {
        val strokes = listOf(listOf(Offset(10f, 20f), Offset(30f, 40f)))

        assertEquals(strokes, strokesFittedToPad(strokes, from = IntSize(600, 400), to = IntSize(1200, 400)))
    }
}
