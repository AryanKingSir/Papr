package com.papr.scanner.presentation.merge

import com.papr.scanner.domain.model.Document

data class MergeConfirmUiState(
    val documents: List<Document> = emptyList(),
    val title: String = "",
    val isMerging: Boolean = false,
)
