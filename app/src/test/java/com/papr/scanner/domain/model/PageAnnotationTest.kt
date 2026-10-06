package com.papr.scanner.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PageAnnotationTest {

    private val pen = InkStroke(
        tool = InkTool.PEN,
        color = 0xFF1A73E8.toInt(),
        width = 0.006f,
        points = listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.15f, 0.22f), NormalizedPoint(0.3f, 0.25f)),
    )
    private val highlighter = InkStroke(
        tool = InkTool.HIGHLIGHTER,
        color = 0xFFFFEB3B.toInt(),
        width = 0.03f,
        points = listOf(NormalizedPoint(0.5f, 0.6f), NormalizedPoint(0.9f, 0.6f)),
    )
    private val note = TextNote(text = "Approved", x = 0.2f, y = 0.7f, color = 0xFF000000.toInt(), size = 0.04f)

    @Test
    fun `encodes and decodes every kind of annotation`() {
        val annotations = listOf(pen, highlighter, note)

        assertEquals(annotations, decodeAnnotations(encodeAnnotations(annotations)))
    }

    @Test
    fun `text survives quotes, separators, newlines and non-latin characters`() {
        val tricky = note.copy(text = "He said \"ok\" | a;b,c\nnext line \\ ✓ नमस्ते 日本")

        assertEquals(listOf(tricky), decodeAnnotations(encodeAnnotations(listOf(tricky))))
    }

    @Test
    fun `keeps a rotated note's turns`() {
        val turned = note.copy(quarterTurns = 3)

        assertEquals(listOf(turned), decodeAnnotations(encodeAnnotations(listOf(turned))))
    }

    @Test
    fun `an empty list encodes to null so the column stays empty`() {
        assertEquals(null, encodeAnnotations(emptyList()))
    }

    @Test
    fun `null or blank decodes to no annotations`() {
        assertEquals(emptyList<PageAnnotation>(), decodeAnnotations(null))
        assertEquals(emptyList<PageAnnotation>(), decodeAnnotations(""))
    }

    @Test
    fun `skips annotation kinds this version doesn't know`() {
        val raw = """[{"type":"sticker","id":7},{"type":"text","text":"Hi","x":0.1,"y":0.2,"color":-16777216,"size":0.04,"turns":0}]"""

        assertEquals(listOf(TextNote("Hi", 0.1f, 0.2f, 0xFF000000.toInt(), 0.04f)), decodeAnnotations(raw))
    }

    @Test
    fun `a stroke turns with the page like a confidential mark does`() {
        val rotated = pen.rotatedClockwise() as InkStroke
        val expected = ConfidentialMarkStroke(pen.points).rotatedClockwise(pageAspect = 1f).points

        assertEquals(expected, rotated.points)
        assertEquals(pen.copy(points = expected), rotated)
    }

    @Test
    fun `a note's anchor moves with the page and the note turns a quarter`() {
        val rotated = note.rotatedClockwise() as TextNote

        assertEquals(1f - note.y, rotated.x, 0.0001f)
        assertEquals(note.x, rotated.y, 0.0001f)
        assertEquals(1, rotated.quarterTurns)
    }

    @Test
    fun `four turns bring a note back to where it started`() {
        var turned: PageAnnotation = note
        repeat(4) { turned = turned.rotatedClockwise() }

        turned as TextNote
        assertEquals(note.x, turned.x, 0.0001f)
        assertEquals(note.y, turned.y, 0.0001f)
        assertEquals(0, turned.quarterTurns)
    }
}
