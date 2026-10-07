package com.papr.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNoteBoundsTest {

    @Test
    fun `an upright note extends right and down from its anchor`() {
        assertEquals(NoteBox(0f, 200f, 0f, 50f), noteBoxAroundAnchor(textWidth = 200f, textHeight = 50f, quarterTurns = 0))
    }

    @Test
    fun `a note turned a quarter clockwise extends left and down from its anchor`() {
        assertEquals(NoteBox(-50f, 0f, 0f, 200f), noteBoxAroundAnchor(textWidth = 200f, textHeight = 50f, quarterTurns = 1))
    }

    @Test
    fun `an upside-down note extends left and up from its anchor`() {
        assertEquals(NoteBox(-200f, 0f, -50f, 0f), noteBoxAroundAnchor(textWidth = 200f, textHeight = 50f, quarterTurns = 2))
    }

    @Test
    fun `a note turned three quarters extends right and up from its anchor`() {
        assertEquals(NoteBox(0f, 50f, -200f, 0f), noteBoxAroundAnchor(textWidth = 200f, textHeight = 50f, quarterTurns = 3))
    }

    @Test
    fun `a note that fits stays where it was put`() {
        assertEquals(300f, anchorKeepingBoxOnPage(anchor = 300f, boxStart = 0f, boxEnd = 200f, pageLength = 1000f))
    }

    @Test
    fun `a note running off the far edge moves back until it ends at the edge`() {
        assertEquals(800f, anchorKeepingBoxOnPage(anchor = 950f, boxStart = 0f, boxEnd = 200f, pageLength = 1000f))
    }

    @Test
    fun `a note running off the near edge moves in until it starts at the edge`() {
        assertEquals(50f, anchorKeepingBoxOnPage(anchor = 10f, boxStart = -50f, boxEnd = 0f, pageLength = 1000f))
    }

    @Test
    fun `a note longer than the page starts at the near edge`() {
        assertEquals(0f, anchorKeepingBoxOnPage(anchor = 400f, boxStart = 0f, boxEnd = 1500f, pageLength = 1000f))
    }
}
