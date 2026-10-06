package com.papr.scanner.presentation.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.data.repository.DocumentDeleter
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import com.papr.scanner.usage.Allowance
import com.papr.scanner.usage.MeteredFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val documentRepository: DocumentRepository,
    private val documentDeleter: DocumentDeleter,
    isProFlow: Flow<Boolean>,
    allowances: Flow<Map<MeteredFeature, Allowance>>,
) : ViewModel() {

    /**
     * Pro owners don't need the "Pro" button in the top bar. Null until it's known, so the button
     * doesn't flash up for Pro owners while the setting loads.
     */
    val isPro: StateFlow<Boolean?> = isProFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Free merges left this month; null for Pro (unlimited) and until it's known. At 0, Merge opens the Pro sheet. */
    val mergesLeft: StateFlow<Int?> = allowances.map { it[MeteredFeature.MERGE]?.remaining }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val searchQuery = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow<String?>(null)
    private val selectedDateRange = MutableStateFlow<LongRange?>(null)
    private val isSelectionMode = MutableStateFlow(false)
    private val selectedDocumentIds = MutableStateFlow<List<Long>>(emptyList())

    private data class SearchResults(
        val documents: List<Document>,
        val searchQuery: String,
        val folders: List<String>,
        val selectedFolder: String?,
        val selectedDateRange: LongRange?,
        val contentMatchedIds: Set<Long>,
    )

    // Kept as its own 5-flow combine (the direct combine() overloads only go up to 5
    // arguments) and merged with the two selection-state flows below in a second stage,
    // rather than trying to combine all 7 sources in one call.
    private val searchResults: Flow<SearchResults> = combine(
        documentRepository.getAllDocuments(),
        documentRepository.getAllPages(),
        searchQuery,
        selectedFolder,
        selectedDateRange,
    ) { documents, pages, query, folder, dateRange ->
        val folders = documents.mapNotNull { it.folder }.distinct().sorted()
        val pagesByDocument = pages.groupBy { it.documentId }

        val filtered = documents
            .filter { folder == null || it.folder == folder }
            .filter { dateRange == null || it.createdAt in dateRange }

        val contentMatchedIds = mutableSetOf<Long>()
        // filtered is already ordered most-recently-updated-first (DocumentDao.getAll()'s
        // own ORDER BY) - filter/mapNotNull preserve that order, and sortedBy is stable, so
        // title matches end up before content-only matches without losing recency order
        // within either group.
        val visible = if (query.isBlank()) {
            filtered
        } else {
            filtered.mapNotNull { document ->
                val pageTexts = pagesByDocument[document.id].orEmpty().map { it.extractedText }
                when (matchDocument(query, document.title, pageTexts)) {
                    SearchMatch.TITLE -> document
                    SearchMatch.CONTENT -> { contentMatchedIds += document.id; document }
                    SearchMatch.NONE -> null
                }
            }.sortedBy { document -> if (document.id in contentMatchedIds) 1 else 0 }
        }

        SearchResults(
            documents = visible,
            searchQuery = query,
            folders = folders,
            selectedFolder = folder,
            selectedDateRange = dateRange,
            contentMatchedIds = contentMatchedIds,
        )
        // Matching every page's OCR text is real work, redone on every keystroke
        // and every page update (e.g. OCR finishing) - keep it off the main thread.
    }.flowOn(Dispatchers.Default)

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        searchResults,
        isSelectionMode,
        selectedDocumentIds,
    ) { results, selectionMode, selectedIds ->
        HomeUiState(
            isLoading = false,
            documents = results.documents,
            searchQuery = results.searchQuery,
            folders = results.folders,
            selectedFolder = results.selectedFolder,
            selectedDateRange = results.selectedDateRange,
            contentMatchedIds = results.contentMatchedIds,
            isSelectionMode = selectionMode,
            selectedDocumentIds = selectedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * What the search field shows. Compose state rather than [HomeUiState.searchQuery]:
     * that only catches up after the async search pipeline runs, and a text field fed a
     * stale value mid-typing resets its cursor, scrambling or duplicating letters.
     */
    var searchFieldText by mutableStateOf("")
        private set

    fun onSearchQueryChange(query: String) {
        searchFieldText = query
        searchQuery.value = query
    }

    fun onFolderSelected(folder: String?) {
        selectedFolder.value = folder
    }

    fun onDateRangeSelected(range: LongRange?) {
        selectedDateRange.value = range
    }

    fun onDocumentLongPress(document: Document) {
        isSelectionMode.value = true
        if (document.id !in selectedDocumentIds.value) {
            selectedDocumentIds.value = selectedDocumentIds.value + document.id
        }
    }

    fun toggleSelection(documentId: Long) {
        val current = selectedDocumentIds.value
        selectedDocumentIds.value = if (documentId in current) current - documentId else current + documentId
    }

    fun clearSelection() {
        isSelectionMode.value = false
        selectedDocumentIds.value = emptyList()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            try {
                // Looked up by ID directly rather than filtered from the current uiState -
                // a document can stay "selected" while an active search/folder/date filter
                // hides it from view, and it still needs to be deleted correctly.
                selectedDocumentIds.value.forEach { id ->
                    documentRepository.getDocument(id)?.let { documentDeleter.delete(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", "Failed to delete documents", e)
                _errors.emit("Couldn't delete every selected document. Please try again.")
            } finally {
                clearSelection()
            }
        }
    }
}

internal enum class SearchMatch { TITLE, CONTENT, NONE }

/** How (if at all) [query] matches a document, given its [title] and its pages' recognized text - a title match outranks a content-only match. */
internal fun matchDocument(query: String, title: String, pageTexts: List<String?>): SearchMatch {
    // Keyboards add a space after a suggested word; it shouldn't stop "budget " matching "Budget 2026".
    val term = query.trim()
    return when {
        title.contains(term, ignoreCase = true) -> SearchMatch.TITLE
        pageTexts.any { it != null && it.contains(term, ignoreCase = true) } -> SearchMatch.CONTENT
        else -> SearchMatch.NONE
    }
}
