package com.papr.scanner.presentation.home

import com.papr.scanner.domain.model.Document

data class HomeUiState(
    // True until the first database read arrives, so the empty state doesn't flash on launch.
    val isLoading: Boolean = true,
    val documents: List<Document> = emptyList(),
    val searchQuery: String = "",
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val selectedDateRange: LongRange? = null,
    val contentMatchedIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val selectedDocumentIds: List<Long> = emptyList(),
) {
    /** Whether an empty result means "nothing matches" rather than "no documents yet". */
    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() || selectedFolder != null || selectedDateRange != null
}
