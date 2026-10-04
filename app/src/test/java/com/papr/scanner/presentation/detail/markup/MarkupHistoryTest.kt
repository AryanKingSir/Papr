package com.papr.scanner.presentation.detail.markup

import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import com.papr.scanner.domain.model.TextNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupHistoryTest {

    private val empty = MarkupSnapshot(emptyList(), emptyList())
    private val oneNote = MarkupSnapshot(listOf(TextNote("a", 0.1f, 0.1f, 0, 0.04f)), emptyList())
    private val oneMark = MarkupSnapshot(
        emptyList(),
        listOf(ConfidentialMarkStroke(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f)))),
    )

    @Test
    fun `starts with nothing to undo or redo`() {
        val history = MarkupHistory()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun `undo hands back the last edit, which redo then repeats`() {
        val history = MarkupHistory()
        val edit = MarkupEdit(pageId = 1, before = empty, after = oneNote)
        history.record(edit)

        assertEquals(edit, history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(edit, history.redo())
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `undoes the newest edit first, across pages`() {
        val history = MarkupHistory()
        val first = MarkupEdit(pageId = 1, before = empty, after = oneNote)
        val second = MarkupEdit(pageId = 2, before = empty, after = oneMark)
        history.record(first)
        history.record(second)

        assertEquals(second, history.undo())
        assertEquals(first, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun `a new edit after undoing drops what could be redone`() {
        val history = MarkupHistory()
        history.record(MarkupEdit(pageId = 1, before = empty, after = oneNote))
        history.undo()

        history.record(MarkupEdit(pageId = 1, before = empty, after = oneMark))

        assertFalse(history.canRedo)
    }

    @Test
    fun `an edit that changed nothing isn't recorded`() {
        val history = MarkupHistory()

        history.record(MarkupEdit(pageId = 1, before = oneNote, after = oneNote))

        assertFalse(history.canUndo)
    }

    @Test
    fun `clear forgets everything`() {
        val history = MarkupHistory()
        history.record(MarkupEdit(pageId = 1, before = empty, after = oneNote))
        history.record(MarkupEdit(pageId = 1, before = oneNote, after = empty))
        history.undo()

        history.clear()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
