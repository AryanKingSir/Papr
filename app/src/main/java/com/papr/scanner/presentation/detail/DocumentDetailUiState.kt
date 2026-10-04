package com.papr.scanner.presentation.detail

import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.detail.markup.ToolStyle

data class DocumentDetailUiState(
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val currentPageIndex: Int = 0,
    // True until the document's first database read arrives - lets the screen
    // tell "still loading" apart from "this document doesn't exist".
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val isDeleted: Boolean = false,
    // Bumped on every page-file mutation (rotate, etc). Page rows can be
    // written back with field-identical values (e.g. after a rotate, which
    // only touches the file on disk), and StateFlow dedupes equal values, so
    // without this the UI would silently miss the update until something
    // else happens to also change a real field.
    val revision: Int = 0,
    val mode: EditorMode = EditorMode.NONE,
    val markupTool: MarkupTool = MarkupTool.PEN,
    val toolStyles: Map<MarkupTool, ToolStyle> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasSavedSignature: Boolean = false,
) {
    val currentPage: Page? get() = pages.getOrNull(currentPageIndex)
    val isMarkingConfidential: Boolean get() = mode == EditorMode.MARKUP && markupTool == MarkupTool.CONFIDENTIAL

    /** In Sign mode the current page's signature is always movable - there's nothing else to do with it there. */
    val isPlacingSignature: Boolean get() = mode == EditorMode.SIGN && currentPage?.signature != null
}

/** Which group of tools the bottom bar is showing. NONE is the main bar of groups. */
enum class EditorMode { NONE, MARKUP, SIGN, PAGES }

enum class MarkupTool { PEN, HIGHLIGHTER, TEXT, ERASER, CONFIDENTIAL }

enum class ExportFormat { PDF, IMAGES }

data class ExportSettings(
    val format: ExportFormat = ExportFormat.PDF,
    val ocrEnabled: Boolean = false,
    val compressToTargetSize: Boolean = false,
    val targetSizeMb: Float = 1f,
    val passwordEnabled: Boolean = false,
    val password: String = "",
)
