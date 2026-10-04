package com.papr.scanner.presentation.detail

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentDeleter
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.Page
import com.papr.scanner.domain.model.clampedTo
import com.papr.scanner.domain.model.defaultSignaturePlacement
import com.papr.scanner.domain.model.rotatedClockwise
import com.papr.scanner.pdf.PasswordProtection
import com.papr.scanner.pdf.PdfEngine
import com.papr.scanner.pdf.PdfGenerationOptions
import com.papr.scanner.pdf.TextRecognitionUnavailableException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DocumentDetailViewModel(
    private val documentId: Long,
    private val documentRepository: DocumentRepository,
    private val documentDeleter: DocumentDeleter,
    private val pdfEngine: PdfEngine,
    private val appContext: Context,
) : ViewModel() {

    /** Screen-local state, kept in one flow so every field (isDeleted included) reliably reaches uiState. */
    private data class LocalState(
        val currentPageIndex: Int = 0,
        val isExporting: Boolean = false,
        val isDeleted: Boolean = false,
        val revision: Int = 0,
        val isMarkingConfidential: Boolean = false,
        val isPlacingSignature: Boolean = false,
    )

    private val localState = MutableStateFlow(LocalState())

    // Serializes every operation that reads or writes a page's files or rows
    // (rotate/move/delete/marks/signature/export), so two overlapping taps can't
    // race each other. Each one re-reads the current rows *inside* the lock
    // rather than trusting the Page the UI handed over, which may predate an
    // edit that was still queued (e.g. two quick mark strokes).
    private val fileMutex = Mutex()

    val uiState: StateFlow<DocumentDetailUiState> = combine(
        documentRepository.getDocumentById(documentId),
        documentRepository.getPagesForDocument(documentId),
        localState,
    ) { document, rawPages, local ->
        val pages = rawPages.sortedBy { it.pageIndex }
        DocumentDetailUiState(
            document = document,
            pages = pages,
            currentPageIndex = local.currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            isLoading = false,
            isExporting = local.isExporting,
            isDeleted = local.isDeleted,
            revision = local.revision,
            isMarkingConfidential = local.isMarkingConfidential,
            isPlacingSignature = local.isPlacingSignature,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState())

    private val _pdfReady = MutableSharedFlow<File>()
    val pdfReady: SharedFlow<File> = _pdfReady.asSharedFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun selectPage(index: Int) {
        localState.update {
            // Signature placement belongs to one page; switching pages ends it.
            if (index == it.currentPageIndex) it else it.copy(currentPageIndex = index, isPlacingSignature = false)
        }
    }

    fun updateTitle(newTitle: String) {
        val title = newTitle.trim()
        if (title.isEmpty()) return
        launchEdit("Couldn't rename the document") {
            val document = documentRepository.getDocument(documentId) ?: return@launchEdit
            documentRepository.updateDocument(document.copy(title = title, updatedAt = System.currentTimeMillis()))
        }
    }

    fun rotatePage(pageId: Long) {
        launchEdit("Couldn't rotate the page") {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val file = File(page.imageUri)
            FileUtils.rotateJpeg(file, 90f)
            // Marks and the signature are stored relative to the image, so they
            // have to turn with it - otherwise a mark would end up over different
            // content than it was hiding.
            val rotated = page.copy(
                confidentialMarks = page.confidentialMarks.map { it.rotatedClockwise() },
                signature = page.signature?.rotatedClockwise(newPageAspect = FileUtils.imageAspectRatio(file)),
            )
            if (rotated != page) documentRepository.updatePage(rotated)
            refreshThumbnailIfFirst(rotated)
            touchDocument()
            localState.update { it.copy(revision = it.revision + 1) }
        }
    }

    fun movePage(pageId: Long, direction: Int) {
        launchEdit("Couldn't move the page") {
            val pages = documentRepository.getPages(documentId)
            val index = pages.indexOfFirst { it.id == pageId }
            val targetIndex = index + direction
            if (index == -1 || targetIndex !in pages.indices) return@launchEdit
            val page = pages[index]
            val other = pages[targetIndex]
            documentRepository.updatePage(page.copy(pageIndex = other.pageIndex))
            documentRepository.updatePage(other.copy(pageIndex = page.pageIndex))
            if (index == 0 || targetIndex == 0) {
                documentRepository.getPages(documentId).firstOrNull()?.let { FileUtils.generateThumbnail(appContext, it) }
            }
            touchDocument()
            // Keep the moved page on screen rather than whatever slid into its old slot.
            localState.update { it.copy(currentPageIndex = targetIndex) }
        }
    }

    fun deletePage(pageId: Long) {
        launchEdit("Couldn't delete the page") {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            documentRepository.deletePage(page)
            withContext(Dispatchers.IO) {
                File(page.imageUri).delete()
                page.signature?.let { File(it.imagePath).delete() }
            }

            val remaining = documentRepository.getPages(documentId)
            if (remaining.isEmpty()) {
                documentRepository.getDocument(documentId)?.let { documentDeleter.delete(it) }
                localState.update { it.copy(isDeleted = true) }
                return@launchEdit
            }
            // Renumber to stay contiguous/zero-based: otherwise a gap opens up
            // wherever a page was deleted, and every "pageIndex == 0" check
            // (thumbnail source) would stop matching the page displayed first.
            val renumbered = remaining.mapIndexed { index, remainingPage ->
                if (remainingPage.pageIndex == index) {
                    remainingPage
                } else {
                    remainingPage.copy(pageIndex = index).also { documentRepository.updatePage(it) }
                }
            }
            documentRepository.getDocument(documentId)?.let { document ->
                documentRepository.updateDocument(
                    document.copy(pageCount = renumbered.size, updatedAt = System.currentTimeMillis())
                )
            }
            FileUtils.generateThumbnail(appContext, renumbered.first())
        }
    }

    fun toggleMarkingConfidential() {
        localState.update {
            val marking = !it.isMarkingConfidential
            it.copy(isMarkingConfidential = marking, isPlacingSignature = it.isPlacingSignature && !marking)
        }
    }

    fun addConfidentialMarkStroke(pageId: Long, stroke: ConfidentialMarkStroke) {
        updatePage(pageId, "Couldn't add the mark") { page ->
            page.copy(confidentialMarks = page.confidentialMarks + stroke)
        }
    }

    fun eraseConfidentialMarkStroke(pageId: Long, stroke: ConfidentialMarkStroke) {
        updatePage(pageId, "Couldn't remove the mark") { page ->
            page.copy(confidentialMarks = page.confidentialMarks - stroke)
        }
    }

    /** Saves a freshly drawn signature onto the page (replacing any existing one) and starts placing it. */
    fun setSignature(pageId: Long, signatureBitmap: Bitmap) {
        launchEdit("Couldn't add the signature") {
            try {
                val page = documentRepository.getPage(pageId) ?: return@launchEdit
                val file = File(FileUtils.documentDir(appContext, documentId), "signature_${UUID.randomUUID()}.png")
                withContext(Dispatchers.IO) {
                    // PNG keeps the transparent background, so only the ink covers the page.
                    file.outputStream().use { signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val signature = defaultSignaturePlacement(
                    imagePath = file.absolutePath,
                    aspectRatio = signatureBitmap.height / signatureBitmap.width.toFloat(),
                    pageAspect = FileUtils.imageAspectRatio(File(page.imageUri)),
                )
                val updated = page.copy(signature = signature)
                documentRepository.updatePage(updated)
                page.signature?.let { old -> withContext(Dispatchers.IO) { File(old.imagePath).delete() } }
                refreshThumbnailIfFirst(updated)
                touchDocument()
                localState.update { it.copy(isPlacingSignature = true, isMarkingConfidential = false) }
            } finally {
                signatureBitmap.recycle()
            }
        }
    }

    fun updateSignaturePlacement(pageId: Long, x: Float, y: Float, width: Float) {
        launchEdit("Couldn't move the signature") {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val signature = page.signature ?: return@launchEdit
            val pageAspect = FileUtils.imageAspectRatio(File(page.imageUri))
            val updated = page.copy(signature = signature.copy(x = x, y = y, width = width).clampedTo(pageAspect))
            if (updated == page) return@launchEdit
            documentRepository.updatePage(updated)
            refreshThumbnailIfFirst(updated)
            touchDocument()
        }
    }

    fun removeSignature(pageId: Long) {
        launchEdit("Couldn't remove the signature") {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val signature = page.signature ?: return@launchEdit
            val updated = page.copy(signature = null)
            documentRepository.updatePage(updated)
            withContext(Dispatchers.IO) { File(signature.imagePath).delete() }
            refreshThumbnailIfFirst(updated)
            touchDocument()
            localState.update { it.copy(isPlacingSignature = false) }
        }
    }

    fun setPlacingSignature(placing: Boolean) {
        localState.update {
            it.copy(isPlacingSignature = placing, isMarkingConfidential = it.isMarkingConfidential && !placing)
        }
    }

    fun export(settings: ExportSettings) {
        if (localState.value.isExporting) return
        viewModelScope.launch {
            localState.update { it.copy(isExporting = true) }
            try {
                val outputFile = fileMutex.withLock {
                    val document = documentRepository.getDocument(documentId) ?: return@launch
                    val pages = documentRepository.getPages(documentId)
                    if (pages.isEmpty()) return@launch
                    val exportFile = File(
                        FileUtils.documentDir(appContext, documentId),
                        "${FileUtils.sanitizeFileName(document.title)}.pdf",
                    )
                    pdfEngine.generatePdf(
                        pages.map { File(it.imageUri) },
                        exportFile,
                        PdfGenerationOptions(
                            ocrEnabled = settings.ocrEnabled,
                            // Keyed by position in the export, which is what PdfEngine's page index means.
                            signaturesByPageIndex = pages.mapIndexedNotNull { index, page ->
                                page.signature?.let { index to it }
                            }.toMap(),
                            confidentialMarksByPageIndex = pages.mapIndexedNotNull { index, page ->
                                page.confidentialMarks.takeIf { it.isNotEmpty() }?.let { index to it }
                            }.toMap(),
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
                    documentRepository.updateDocument(
                        document.copy(pdfUri = exportFile.absolutePath, updatedAt = System.currentTimeMillis())
                    )
                    // The file is named after the title, so after a rename the previous
                    // export would otherwise stay behind in the document's folder for good.
                    document.pdfUri?.takeIf { it != exportFile.absolutePath }?.let { File(it).delete() }
                    exportFile
                }
                _pdfReady.emit(outputFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TextRecognitionUnavailableException) {
                Log.w("Papr", "Export with OCR attempted before text recognition was ready", e)
                _errors.emit(
                    "Searchable text isn't ready yet - its language data is still downloading. " +
                        "Try again in a minute, or export without searchable text."
                )
            } catch (e: Exception) {
                Log.e("Papr", "Export failed", e)
                _errors.emit("Export failed. Please try again.")
            } finally {
                localState.update { it.copy(isExporting = false) }
            }
        }
    }

    /** Runs [edit] under [fileMutex], reporting any failure as [errorMessage] instead of crashing. */
    private fun launchEdit(errorMessage: String, edit: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                fileMutex.withLock { edit() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", errorMessage, e)
                _errors.emit(errorMessage)
            }
        }
    }

    /** Applies [transform] to the page's current row and saves it, keeping the home thumbnail in sync. */
    private fun updatePage(pageId: Long, errorMessage: String, transform: (Page) -> Page) {
        launchEdit(errorMessage) {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val updated = transform(page)
            if (updated == page) return@launchEdit
            documentRepository.updatePage(updated)
            refreshThumbnailIfFirst(updated)
            touchDocument()
        }
    }

    private suspend fun refreshThumbnailIfFirst(page: Page) {
        if (page.pageIndex == 0) FileUtils.generateThumbnail(appContext, page)
    }

    /** Bumps updatedAt so the home grid's thumbnail (same file path, new bytes) also refreshes when we return to it. */
    private suspend fun touchDocument() {
        val document = documentRepository.getDocument(documentId) ?: return
        documentRepository.updateDocument(document.copy(updatedAt = System.currentTimeMillis()))
    }

    private companion object {
        const val BYTES_PER_MB = 1024 * 1024
    }
}
