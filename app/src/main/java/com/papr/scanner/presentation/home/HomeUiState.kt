package com.papr.scanner.presentation.home

import com.papr.scanner.domain.model.Document

data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val searchQuery: String = "",
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val selectedDateRange: LongRange? = null,
    val contentMatchedIds: Set<Long> = emptySet(),
)
