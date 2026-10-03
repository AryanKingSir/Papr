package com.papr.scanner.presentation.merge

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.data.repository.DocumentMerger
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MergeConfirmViewModel(
    documentIds: List<Long>,
    private val documentRepository: DocumentRepository,
    private val documentMerger: DocumentMerger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeConfirmUiState())
    val uiState: StateFlow<MergeConfirmUiState> = _uiState.asStateFlow()

    private val _mergeComplete = MutableSharedFlow<Long>()
    val mergeComplete: SharedFlow<Long> = _mergeComplete.asSharedFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        viewModelScope.launch {
            val documents = documentIds.mapNotNull { id -> documentRepository.getDocumentById(id).first() }
            _uiState.value = MergeConfirmUiState(
                documents = documents,
                title = defaultMergeTitle(documents.map { it.title }),
            )
        }
    }

    fun onTitleChange(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun moveDocument(document: Document, direction: Int) {
        val documents = _uiState.value.documents
        val index = documents.indexOfFirst { it.id == document.id }
        if (index == -1) return
        updateDocuments(reordered(documents, index, direction))
    }

    fun removeDocument(document: Document) {
        updateDocuments(_uiState.value.documents.filter { it.id != document.id })
    }

    private fun updateDocuments(documents: List<Document>) {
        val state = _uiState.value
        _uiState.value = state.copy(
            documents = documents,
            title = titleAfterListChange(state.title, state.documents.map { it.title }, documents.map { it.title }),
        )
    }

    fun merge() {
        val state = _uiState.value
        if (state.documents.size < 2 || state.title.isBlank() || state.isMerging) return
        viewModelScope.launch {
            _uiState.value = state.copy(isMerging = true)
            try {
                val newDocumentId = documentMerger.merge(state.documents.map { it.id }, state.title.trim())
                _mergeComplete.emit(newDocumentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", "Merge failed", e)
                _uiState.value = _uiState.value.copy(isMerging = false)
                _errors.emit("Couldn't merge these documents. Please try again.")
            }
        }
    }
}

private const val MAX_DEFAULT_TITLE_LENGTH = 60

/** Builds a default merged-document title by joining the source titles, truncated with an ellipsis if the join is too long. */
internal fun defaultMergeTitle(titles: List<String>): String {
    val joined = titles.joinToString(" + ")
    return if (joined.length > MAX_DEFAULT_TITLE_LENGTH) joined.take(MAX_DEFAULT_TITLE_LENGTH).trimEnd() + "…" else joined
}

/**
 * Keeps the default title in step with the list (order and removals) until the user
 * types their own - an edited title is left exactly as they wrote it.
 */
internal fun titleAfterListChange(currentTitle: String, oldTitles: List<String>, newTitles: List<String>): String =
    if (currentTitle == defaultMergeTitle(oldTitles)) defaultMergeTitle(newTitles) else currentTitle

/** Swaps the item at [index] with its neighbor in [direction] (-1 earlier, +1 later); returns [items] unchanged if that neighbor is out of bounds. */
internal fun <T> reordered(items: List<T>, index: Int, direction: Int): List<T> {
    val targetIndex = index + direction
    if (index !in items.indices || targetIndex !in items.indices) return items
    return items.toMutableList().apply {
        val temp = this[index]
        this[index] = this[targetIndex]
        this[targetIndex] = temp
    }
}
