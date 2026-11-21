package com.papr.scanner.presentation.merge

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.data.repository.DocumentDeleter
import com.papr.scanner.data.repository.DocumentMerger
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MergeConfirmViewModel(
    documentIds: List<Long>,
    private val savedState: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val documentMerger: DocumentMerger,
    private val documentDeleter: DocumentDeleter,
    deleteOriginalsByDefault: Flow<Boolean>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeConfirmUiState())
    val uiState: StateFlow<MergeConfirmUiState> = _uiState.asStateFlow()

    // A channel, so a merge that finishes mid-rotation still opens the new document.
    private val _mergeComplete = Channel<Long>(Channel.BUFFERED)
    val mergeComplete: Flow<Long> = _mergeComplete.receiveAsFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        viewModelScope.launch {
            // The user's reordering, removals and title edits come back if the process
            // was killed while they were on this screen.
            val ids = savedState.get<LongArray>(KEY_ORDER)?.toList() ?: documentIds
            val documents = ids.mapNotNull { id -> documentRepository.getDocumentById(id).first() }
            _uiState.value = MergeConfirmUiState(
                documents = documents,
                title = savedState[KEY_TITLE] ?: defaultMergeTitle(documents.map { it.title }),
                deleteOriginals = savedState[KEY_DELETE_ORIGINALS] ?: deleteOriginalsByDefault.first(),
            )
        }
    }

    fun onTitleChange(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
        savedState[KEY_TITLE] = title
    }

    fun setDeleteOriginals(delete: Boolean) {
        _uiState.value = _uiState.value.copy(deleteOriginals = delete)
        savedState[KEY_DELETE_ORIGINALS] = delete
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
        savedState[KEY_ORDER] = documents.map { it.id }.toLongArray()
        savedState[KEY_TITLE] = _uiState.value.title
    }

    fun merge() {
        val state = _uiState.value
        if (state.documents.size < 2 || state.title.isBlank() || state.isMerging) return
        viewModelScope.launch {
            _uiState.value = state.copy(isMerging = true)
            try {
                val newDocumentId = documentMerger.merge(state.documents.map { it.id }, state.title.trim())
                // Only once the merged copy exists, so a failed merge never loses the originals.
                if (state.deleteOriginals) deleteOriginals(state.documents)
                _mergeComplete.send(newDocumentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", "Merge failed", e)
                _uiState.value = _uiState.value.copy(isMerging = false)
                _errors.emit("Couldn't merge these documents. Please try again.")
            }
        }
    }

    /** One that can't be deleted stays behind rather than undoing a merge that already succeeded. */
    private suspend fun deleteOriginals(documents: List<Document>) {
        for (document in documents) {
            try {
                documentDeleter.delete(document)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", "Couldn't delete merged original ${document.id}", e)
            }
        }
    }
}

private const val MAX_DEFAULT_TITLE_LENGTH = 60
private const val KEY_ORDER = "merge_order"
private const val KEY_TITLE = "merge_title"
private const val KEY_DELETE_ORIGINALS = "merge_delete_originals"

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
