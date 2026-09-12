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

    val uiState: StateFlow<HomeUiState> = combine(
        documentRepository.getAllDocuments(),
        searchQuery,
        selectedFolder,
    ) { documents, query, folder ->
        val folders = documents.mapNotNull { it.folder }.distinct().sorted()
        val visible = documents
            .filter { folder == null || it.folder == folder }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        HomeUiState(documents = visible, searchQuery = query, folders = folders, selectedFolder = folder)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onFolderSelected(folder: String?) {
        selectedFolder.value = folder
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
