package com.papr.scanner.presentation.detail.markup

import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.PageAnnotation

/** A page's markup at one moment: everything undo/redo can change. */
data class MarkupSnapshot(
    val annotations: List<PageAnnotation>,
    val confidentialMarks: List<ConfidentialMarkStroke>,
)

/** One markup change on one page, stored as whole before/after snapshots so undoing is just writing [before] back. */
data class MarkupEdit(val pageId: Long, val before: MarkupSnapshot, val after: MarkupSnapshot)

/** Undo/redo for one Markup session; it spans page switches and is cleared when the session ends. */
class MarkupHistory {
    private val undoStack = ArrayDeque<MarkupEdit>()
    private val redoStack = ArrayDeque<MarkupEdit>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(edit: MarkupEdit) {
        if (edit.before == edit.after) return
        undoStack.addLast(edit)
        redoStack.clear()
    }

    /** The edit to reverse (write its [MarkupEdit.before]), or null when there's nothing left. */
    fun undo(): MarkupEdit? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }

    /** The edit to repeat (write its [MarkupEdit.after]), or null when there's nothing undone. */
    fun redo(): MarkupEdit? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
