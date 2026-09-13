package com.papr.scanner.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val documentRepository: DocumentRepository,
    private val appContext: Context,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow<String?>(null)
    private val selectedDateRange = MutableStateFlow<LongRange?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
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

        HomeUiState(
            documents = visible,
            searchQuery = query,
            folders = folders,
            selectedFolder = folder,
            selectedDateRange = dateRange,
            contentMatchedIds = contentMatchedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onFolderSelected(folder: String?) {
        selectedFolder.value = folder
    }

    fun onDateRangeSelected(range: LongRange?) {
        selectedDateRange.value = range
    }

    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            documentRepository.deleteDocument(document) // Room cascade-deletes the page rows
            withContext(Dispatchers.IO) {
                FileUtils.documentDir(appContext, document.id).deleteRecursively()
                FileUtils.thumbnailFile(appContext, document.id).delete()
            }
        }
    }
}

internal enum class SearchMatch { TITLE, CONTENT, NONE }

/** How (if at all) [query] matches a document, given its [title] and its pages' recognized text - a title match outranks a content-only match. */
internal fun matchDocument(query: String, title: String, pageTexts: List<String?>): SearchMatch {
    return when {
        title.contains(query, ignoreCase = true) -> SearchMatch.TITLE
        pageTexts.any { it != null && it.contains(query, ignoreCase = true) } -> SearchMatch.CONTENT
        else -> SearchMatch.NONE
    }
}
