package com.papr.scanner.presentation.detail

import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page

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
    val isMarkingConfidential: Boolean = false,
    val isPlacingSignature: Boolean = false,
)

data class ExportSettings(
    val ocrEnabled: Boolean = false,
    val compressToTargetSize: Boolean = false,
    val targetSizeMb: Float = 1f,
    val passwordEnabled: Boolean = false,
    val password: String = "",
)
