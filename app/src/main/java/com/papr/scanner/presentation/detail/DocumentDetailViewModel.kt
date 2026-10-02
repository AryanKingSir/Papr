package com.papr.scanner.presentation.detail

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.pdf.PasswordProtection
import com.papr.scanner.pdf.PdfEngine
import com.papr.scanner.pdf.PdfGenerationOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DocumentDetailViewModel(
    private val documentId: Long,
    private val documentRepository: DocumentRepository,
    private val pdfEngine: PdfEngine,
    private val appContext: Context,
) : ViewModel() {

    private val currentPageIndex = MutableStateFlow(0)
    private val pendingSignature = MutableStateFlow<PendingSignature?>(null)
    private val isExporting = MutableStateFlow(false)
    private val isDeleted = MutableStateFlow(false)
    private val refreshTrigger = MutableStateFlow(0)
    private val isMarkingConfidential = MutableStateFlow(false)

    // Serializes every operation that reads or writes a page's image file
    // (rotate/move/delete/export all touch the same on-disk JPEGs), so two
    // overlapping taps can't race a write against a read of the same file.
    private val fileMutex = Mutex()

    private data class FlowsSnapshot(
        val document: Document?,
        val pages: List<Page>,
        val currentPageIndex: Int,
        val pendingSignature: PendingSignature?,
        val isExporting: Boolean,
    )

    private val flowsSnapshot = combine(
        documentRepository.getDocumentById(documentId),
        documentRepository.getPagesForDocument(documentId),
        currentPageIndex,
        pendingSignature,
        isExporting,
    ) { document, rawPages, pageIndex, signature, exporting ->
        FlowsSnapshot(document, rawPages.sortedBy { it.pageIndex }, pageIndex, signature, exporting)
    }

    val uiState: StateFlow<DocumentDetailUiState> = combine(
        flowsSnapshot,
        refreshTrigger,
        isMarkingConfidential,
    ) { snapshot, revision, marking ->
        DocumentDetailUiState(
            document = snapshot.document,
            pages = snapshot.pages,
            currentPageIndex = snapshot.currentPageIndex.coerceIn(0, (snapshot.pages.size - 1).coerceAtLeast(0)),
            pendingSignature = snapshot.pendingSignature,
            isExporting = snapshot.isExporting,
            isDeleted = isDeleted.value,
            revision = revision,
            isMarkingConfidential = marking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState())

    private val _pdfReady = MutableSharedFlow<File>()
    val pdfReady: SharedFlow<File> = _pdfReady.asSharedFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun selectPage(index: Int) {
        currentPageIndex.value = index
    }

    fun updateTitle(newTitle: String) {
        val document = uiState.value.document ?: return
        viewModelScope.launch {
            documentRepository.updateDocument(document.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
        }
    }

    fun rotatePage(page: Page) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    FileUtils.rotateJpeg(File(page.imageUri), 90f)
                    // page.imageUri is already known to be correct here - no need to
                    // consult uiState.value.pages, which is reactive and can still
                    // reflect pre-mutation data at this exact point (see deletePage's
                    // history: reading it right after a DB write is a real race, not
                    // a hypothetical one).
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, page)
                    }
                    touchDocument()
                }
            }.onSuccess {
                refreshTrigger.value++
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to rotate page")
            }
        }
    }

    fun movePage(page: Page, direction: Int) {
        val pages = uiState.value.pages
        val currentIdx = pages.indexOfFirst { it.id == page.id }
        val targetIdx = currentIdx + direction
        if (currentIdx == -1 || targetIdx !in pages.indices) return
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val other = pages[targetIdx]
                    documentRepository.updatePage(page.copy(pageIndex = other.pageIndex))
                    documentRepository.updatePage(other.copy(pageIndex = page.pageIndex))
                    // Whichever of the two swapped pages now sits at index 0 (computed
                    // from the swap we just made, not re-read from reactive state).
                    val newFirstPage = when (0) {
                        other.pageIndex -> page
                        page.pageIndex -> other
                        else -> null
                    }
                    newFirstPage?.let {
                        FileUtils.generateThumbnail(appContext, it)
                    }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to reorder page")
            }
        }
    }

    fun deletePage(page: Page) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    documentRepository.deletePage(page)
                    withContext(Dispatchers.IO) { File(page.imageUri).delete() }

                    val remaining = uiState.value.pages.filter { it.id != page.id }.sortedBy { it.pageIndex }
                    if (remaining.isEmpty()) {
                        deleteDocumentLocked()
                        return@withLock
                    }
                    // Renumber to stay contiguous/zero-based: otherwise a gap opens
                    // up wherever a page was deleted, and every "pageIndex == 0"
                    // check elsewhere (rotate/move's thumbnail-source logic) would
                    // silently stop matching the page that's actually displayed first.
                    remaining.forEachIndexed { index, remainingPage ->
                        if (remainingPage.pageIndex != index) {
                            documentRepository.updatePage(remainingPage.copy(pageIndex = index))
                        }
                    }
                    uiState.value.document?.let { document ->
                        documentRepository.updateDocument(
                            document.copy(pageCount = remaining.size, updatedAt = System.currentTimeMillis())
                        )
                    }
                    FileUtils.generateThumbnail(appContext, remaining.first())
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to delete page")
            }
        }
    }

    fun deleteDocument() {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock { deleteDocumentLocked() }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to delete document")
            }
        }
    }

    /** Must only be called while holding [fileMutex]. */
    private suspend fun deleteDocumentLocked() {
        val document = uiState.value.document ?: return
        documentRepository.deleteDocument(document)
        withContext(Dispatchers.IO) {
            FileUtils.documentDir(appContext, document.id).deleteRecursively()
            FileUtils.thumbnailFile(appContext, document.id).delete()
        }
        isDeleted.value = true
    }

    fun setPendingSignature(bitmap: Bitmap) {
        pendingSignature.value = PendingSignature(bitmap, uiState.value.currentPageIndex)
    }

    fun clearPendingSignature() {
        pendingSignature.value = null
    }

    fun toggleMarkingConfidential() {
        isMarkingConfidential.value = !isMarkingConfidential.value
    }

    fun addConfidentialMarkStroke(page: Page, stroke: ConfidentialMarkStroke) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val updated = page.copy(confidentialMarks = page.confidentialMarks + stroke)
                    documentRepository.updatePage(updated)
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, updated)
                    }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to add confidential mark")
            }
        }
    }

    fun eraseConfidentialMarkStroke(page: Page, stroke: ConfidentialMarkStroke) {
        viewModelScope.launch {
            runCatching {
                fileMutex.withLock {
                    val updated = page.copy(confidentialMarks = page.confidentialMarks - stroke)
                    documentRepository.updatePage(updated)
                    if (page.pageIndex == 0) {
                        FileUtils.generateThumbnail(appContext, updated)
                    }
                    touchDocument()
                }
            }.onFailure { e ->
                _errors.emit(e.message ?: "Failed to erase confidential mark")
            }
        }
    }

    fun export(settings: ExportSettings) {
        val state = uiState.value
        val document = state.document ?: return
        if (state.pages.isEmpty()) return

        viewModelScope.launch {
            isExporting.value = true
            try {
                val outputFile = fileMutex.withLock {
                    val pageFiles = state.pages.map { File(it.imageUri) }
                    val fileName = "${FileUtils.sanitizeFileName(document.title)}.pdf"
                    val exportFile = File(FileUtils.documentDir(appContext, documentId), fileName)

                    val confidentialMarksByPageIndex = state.pages
                        .filter { it.confidentialMarks.isNotEmpty() }
                        .associate { it.pageIndex to it.confidentialMarks }

                    pdfEngine.generatePdf(
                        pageFiles,
                        exportFile,
                        PdfGenerationOptions(
                            ocrEnabled = settings.ocrEnabled,
                            signaturesByPageIndex = state.pages.mapIndexedNotNull { index, page ->
                                page.signature?.let { index to it }
                            }.toMap(),
                            confidentialMarksByPageIndex = confidentialMarksByPageIndex,
                            passwordProtection = settings.password
                                .takeIf { settings.passwordEnabled && it.isNotBlank() }
                                ?.let { PasswordProtection(userPassword = it) },
                            targetSizeBytes = if (settings.compressToTargetSize) {
                                (settings.targetSizeMb * BYTES_PER_MB).toLong()
                            } else {
                                null
                            },
                        ),
                    )
                }

                documentRepository.updateDocument(
                    document.copy(pdfUri = outputFile.absolutePath, updatedAt = System.currentTimeMillis())
                )
                _pdfReady.emit(outputFile)
            } catch (e: Exception) {
                _errors.emit(e.message ?: "Export failed")
            } finally {
                isExporting.value = false
            }
        }
    }

    /** Bumps updatedAt so the home grid's thumbnail (same file path, new bytes) also refreshes when we return to it. */
    private suspend fun touchDocument() {
        val document = uiState.value.document ?: return
        documentRepository.updateDocument(document.copy(updatedAt = System.currentTimeMillis()))
    }

    private companion object {
        const val BYTES_PER_MB = 1024 * 1024
    }
}
