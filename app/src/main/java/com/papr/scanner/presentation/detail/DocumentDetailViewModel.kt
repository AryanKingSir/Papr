package com.papr.scanner.presentation.detail

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.local.SavedSignatureStore
import com.papr.scanner.data.repository.DocumentDeleter
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.domain.model.Page
import com.papr.scanner.domain.model.PageAnnotation
import com.papr.scanner.domain.model.TextNote
import com.papr.scanner.domain.model.clampedTo
import com.papr.scanner.domain.model.defaultSignaturePlacement
import com.papr.scanner.domain.model.rotatedClockwise
import com.papr.scanner.pdf.PageImageExporter
import com.papr.scanner.pdf.PasswordProtection
import com.papr.scanner.pdf.PdfEngine
import com.papr.scanner.pdf.PdfGenerationOptions
import com.papr.scanner.pdf.TextRecognitionUnavailableException
import com.papr.scanner.pdf.overlays
import com.papr.scanner.presentation.detail.markup.MarkupEdit
import com.papr.scanner.presentation.detail.markup.MarkupHistory
import com.papr.scanner.presentation.detail.markup.MarkupSnapshot
import com.papr.scanner.presentation.detail.markup.ToolStyle
import com.papr.scanner.presentation.detail.markup.defaultStyle
import com.papr.scanner.usage.Allowance
import com.papr.scanner.usage.MeteredFeature
import com.papr.scanner.usage.UsageTracker
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
import kotlinx.coroutines.flow.first
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
    private val pageImageExporter: PageImageExporter,
    private val usageTracker: UsageTracker,
    private val savedSignatureStore: SavedSignatureStore,
    private val appContext: Context,
) : ViewModel() {

    /** Screen-local state, kept in one flow so every field (isDeleted included) reliably reaches uiState. */
    private data class LocalState(
        val currentPageIndex: Int = 0,
        val isExporting: Boolean = false,
        val isDeleted: Boolean = false,
        val revision: Int = 0,
        val mode: EditorMode = EditorMode.NONE,
        val markupTool: MarkupTool = MarkupTool.PEN,
        val toolStyles: Map<MarkupTool, ToolStyle> = listOf(MarkupTool.PEN, MarkupTool.HIGHLIGHTER, MarkupTool.TEXT)
            .associateWith { it.defaultStyle() },
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val hasSavedSignature: Boolean = false,
    )

    private val localState = MutableStateFlow(LocalState())

    // Serializes every operation that reads or writes a page's files or rows
    // (rotate/move/delete/marks/signature/export), so two overlapping taps can't
    // race each other. Each one re-reads the current rows *inside* the lock
    // rather than trusting the Page the UI handed over, which may predate an
    // edit that was still queued (e.g. two quick mark strokes).
    private val fileMutex = Mutex()

    private val markupHistory = MarkupHistory()

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
            mode = local.mode,
            markupTool = local.markupTool,
            toolStyles = local.toolStyles,
            canUndo = local.canUndo,
            canRedo = local.canRedo,
            hasSavedSignature = local.hasSavedSignature,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState())

    private val _pdfReady = MutableSharedFlow<File>()
    val pdfReady: SharedFlow<File> = _pdfReady.asSharedFlow()

    private val _imagesReady = MutableSharedFlow<List<File>>()
    val imagesReady: SharedFlow<List<File>> = _imagesReady.asSharedFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val isPro: StateFlow<Boolean> = usageTracker.isPro.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val allowances: StateFlow<Map<MeteredFeature, Allowance>> =
        usageTracker.allowances.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Asks the screen to show the Pro sheet, with the reason it's being shown. */
    private val _proSheetRequests = MutableSharedFlow<String>()
    val proSheetRequests: SharedFlow<String> = _proSheetRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            savedSignatureStore.state.collect { saved -> localState.update { it.copy(hasSavedSignature = saved != null) } }
        }
    }

    fun selectPage(index: Int) {
        localState.update { it.copy(currentPageIndex = index) }
    }

    /** Swaps the bottom bar to a group of tools (or back to the groups, with NONE). */
    fun openMode(mode: EditorMode) {
        // Undo covers one Markup session: leaving Markup ends it.
        if (mode != EditorMode.MARKUP) markupHistory.clear()
        localState.update {
            // Confidential is only kept while it's in use: reopening Markup starts on a free tool.
            val tool = if (mode == EditorMode.MARKUP && it.markupTool == MarkupTool.CONFIDENTIAL) MarkupTool.PEN else it.markupTool
            it.copy(mode = mode, markupTool = tool, canUndo = markupHistory.canUndo, canRedo = markupHistory.canRedo)
        }
    }

    fun closeMode() = openMode(EditorMode.NONE)

    /**
     * Adding confidential marks needs Pro, but a free user can still pick the
     * tool on a page that already has marks (made before Pro was needed, or
     * carried in by a merge) to remove them.
     */
    fun selectMarkupTool(tool: MarkupTool, pageHasMarks: Boolean) {
        viewModelScope.launch {
            if (tool != MarkupTool.CONFIDENTIAL || pageHasMarks || usageTracker.isPro.first()) {
                localState.update { it.copy(markupTool = tool) }
            } else {
                _proSheetRequests.emit(MARKS_NEED_PRO)
            }
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

    fun rotatePage(pageId: Long) = rotatePages(listOf(pageId))

    fun rotatePages(pageIds: Collection<Long>) {
        launchEdit("Couldn't rotate the page") {
            for (pageId in pageIds) {
                val page = documentRepository.getPage(pageId) ?: continue
                val file = File(page.imageUri)
                FileUtils.rotateJpeg(file, 90f)
                // Marks, markup and the signature are stored relative to the image, so they
                // have to turn with it - otherwise a mark would end up over different
                // content than it was hiding.
                val rotated = page.copy(
                    confidentialMarks = page.confidentialMarks.map { it.rotatedClockwise() },
                    annotations = page.annotations.map { it.rotatedClockwise() },
                    signature = page.signature?.rotatedClockwise(newPageAspect = FileUtils.imageAspectRatio(file)),
                )
                if (rotated != page) documentRepository.updatePage(rotated)
                refreshThumbnailIfFirst(rotated)
            }
            touchDocument()
            localState.update { it.copy(revision = it.revision + 1) }
        }
    }

    /** Saves a new page order (page ids, first to last), keeping the page that was on screen on screen. */
    fun reorderPages(orderedIds: List<Long>) {
        launchEdit("Couldn't move the page") {
            val pages = documentRepository.getPages(documentId)
            val changed = pagesInNewOrder(pages, orderedIds)
            if (changed.isEmpty()) return@launchEdit
            val shownId = pages.getOrNull(localState.value.currentPageIndex)?.id
            documentRepository.updatePages(changed)
            val reordered = documentRepository.getPages(documentId)
            if (changed.any { it.pageIndex == 0 }) FileUtils.generateThumbnail(appContext, reordered.first())
            touchDocument()
            val shownIndex = reordered.indexOfFirst { it.id == shownId }
            if (shownIndex != -1) localState.update { it.copy(currentPageIndex = shownIndex) }
        }
    }

    fun deletePage(pageId: Long) = deletePages(listOf(pageId))

    fun deletePages(pageIds: Collection<Long>) {
        launchEdit("Couldn't delete the page") {
            for (pageId in pageIds) {
                val page = documentRepository.getPage(pageId) ?: continue
                documentRepository.deletePage(page)
                withContext(Dispatchers.IO) {
                    File(page.imageUri).delete()
                    page.signature?.let { File(it.imagePath).delete() }
                }
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
            val renumbered = pagesInNewOrder(remaining, remaining.map { it.id })
            if (renumbered.isNotEmpty()) documentRepository.updatePages(renumbered)
            documentRepository.getDocument(documentId)?.let { document ->
                documentRepository.updateDocument(
                    document.copy(pageCount = remaining.size, updatedAt = System.currentTimeMillis())
                )
            }
            FileUtils.generateThumbnail(appContext, documentRepository.getPages(documentId).first())
        }
    }

    fun setToolStyle(tool: MarkupTool, style: ToolStyle) {
        localState.update { it.copy(toolStyles = it.toolStyles + (tool to style)) }
    }

    fun addAnnotation(pageId: Long, annotation: PageAnnotation) {
        editMarkup(pageId, "Couldn't save the markup") { page -> page.copy(annotations = page.annotations + annotation) }
    }

    /** Removes what one eraser gesture passed over, as a single undo step. */
    fun eraseMarkup(pageId: Long, annotations: List<PageAnnotation>, marks: List<ConfidentialMarkStroke>) {
        editMarkup(pageId, "Couldn't erase the markup") { page ->
            page.copy(
                annotations = page.annotations - annotations.toSet(),
                confidentialMarks = page.confidentialMarks - marks.toSet(),
            )
        }
    }

    /** Adds [updated] (when [existing] is null), replaces [existing] with it, or deletes [existing] (when [updated] is null). */
    fun saveTextNote(pageId: Long, existing: TextNote?, updated: TextNote?) {
        editMarkup(pageId, "Couldn't save the text") { page ->
            val annotations = when {
                existing == null -> page.annotations + listOfNotNull(updated)
                updated == null -> page.annotations - existing
                else -> page.annotations.map { if (it == existing) updated else it }
            }
            page.copy(annotations = annotations)
        }
    }

    fun addConfidentialMarkStroke(pageId: Long, stroke: ConfidentialMarkStroke) {
        viewModelScope.launch {
            if (!usageTracker.isPro.first()) {
                _proSheetRequests.emit(MARKS_NEED_PRO)
                return@launch
            }
            editMarkup(pageId, "Couldn't add the mark") { page ->
                page.copy(confidentialMarks = page.confidentialMarks + stroke)
            }
        }
    }

    fun eraseConfidentialMarkStroke(pageId: Long, stroke: ConfidentialMarkStroke) {
        editMarkup(pageId, "Couldn't remove the mark") { page ->
            page.copy(confidentialMarks = page.confidentialMarks - stroke)
        }
    }

    // Undoing never needs Pro: it only puts back what the page had a moment ago.
    fun undoMarkup() {
        markupHistory.undo()?.let { writeMarkup(it.pageId, it.before, "Couldn't undo") }
        publishHistory()
    }

    fun redoMarkup() {
        markupHistory.redo()?.let { writeMarkup(it.pageId, it.after, "Couldn't redo") }
        publishHistory()
    }

    /**
     * Saves a freshly drawn signature onto the page (replacing any existing one),
     * ready to place in Sign mode - and, when [remember] is set and no signature
     * is saved yet, keeps it as the saved signature. A saved signature is only
     * ever replaced from Settings.
     */
    fun setSignature(pageId: Long, signatureBitmap: Bitmap, remember: Boolean) {
        launchEdit("Couldn't add the signature") {
            try {
                val page = documentRepository.getPage(pageId) ?: return@launchEdit
                val file = newSignatureFile()
                withContext(Dispatchers.IO) {
                    // PNG keeps the transparent background, so only the ink covers the page.
                    file.outputStream().use { signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                if (remember && savedSignatureStore.state.value == null) savedSignatureStore.saveCopyOf(file)
                placeSignature(page, file, aspectRatio = signatureBitmap.height / signatureBitmap.width.toFloat())
            } finally {
                signatureBitmap.recycle()
            }
        }
    }

    /** Puts a copy of the saved signature on the page - a copy, so the document folder stays self-contained. */
    fun useSavedSignature(pageId: Long) {
        launchEdit("Couldn't add the saved signature") {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val file = newSignatureFile()
            if (!savedSignatureStore.copyTo(file)) return@launchEdit
            // A signature's aspect ratio is height over width; the image helper gives width over height.
            placeSignature(page, file, aspectRatio = 1f / FileUtils.imageAspectRatio(file))
        }
    }

    private fun newSignatureFile() = File(FileUtils.documentDir(appContext, documentId), "signature_${UUID.randomUUID()}.png")

    /** Must run inside [launchEdit]: replaces [page]'s signature with [file], at the default spot. */
    private suspend fun placeSignature(page: Page, file: File, aspectRatio: Float) {
        val signature = defaultSignaturePlacement(
            imagePath = file.absolutePath,
            aspectRatio = aspectRatio,
            pageAspect = FileUtils.imageAspectRatio(File(page.imageUri)),
        )
        val updated = page.copy(signature = signature)
        documentRepository.updatePage(updated)
        page.signature?.let { old -> withContext(Dispatchers.IO) { File(old.imagePath).delete() } }
        refreshThumbnailIfFirst(updated)
        touchDocument()
        localState.update { it.copy(mode = EditorMode.SIGN) }
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
        }
    }

    fun export(settings: ExportSettings) {
        if (localState.value.isExporting) return
        if (settings.format == ExportFormat.IMAGES) {
            exportImages()
            return
        }
        val features = meteredFeaturesFor(settings)
        viewModelScope.launch {
            localState.update { it.copy(isExporting = true) }
            try {
                // The sheet locks used-up options, but it may have been opened before the last use.
                usageTracker.firstUsedUp(features)?.let { usedUp ->
                    _proSheetRequests.emit(usedUp.limitReachedMessage)
                    return@launch
                }
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
                            overlaysByPageIndex = pages.mapIndexedNotNull { index, page ->
                                page.overlays().takeUnless { it.isEmpty }?.let { index to it }
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
                // Only a finished export counts against the free plan's limits.
                usageTracker.recordUse(features)
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

    /** Every page as a JPG with everything drawn in. Free: it uses none of the limited PDF options. */
    private fun exportImages() {
        viewModelScope.launch {
            localState.update { it.copy(isExporting = true) }
            try {
                val images = fileMutex.withLock {
                    val document = documentRepository.getDocument(documentId) ?: return@launch
                    val pages = documentRepository.getPages(documentId)
                    if (pages.isEmpty()) return@launch
                    pageImageExporter.export(
                        pages.map { File(it.imageUri) to it.overlays() },
                        // Inside the document's folder: shareable through the FileProvider, and
                        // deleted along with the document.
                        File(FileUtils.documentDir(appContext, documentId), "images"),
                        FileUtils.sanitizeFileName(document.title),
                    )
                }
                _imagesReady.emit(images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", "Image export failed", e)
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

    /** Like [updatePage], and records the change so it can be undone. */
    private fun editMarkup(pageId: Long, errorMessage: String, transform: (Page) -> Page) {
        launchEdit(errorMessage) {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val updated = transform(page)
            if (updated == page) return@launchEdit
            savePage(updated)
            markupHistory.record(MarkupEdit(pageId, page.markupSnapshot(), updated.markupSnapshot()))
            publishHistory()
        }
    }

    private fun writeMarkup(pageId: Long, snapshot: MarkupSnapshot, errorMessage: String) {
        updatePage(pageId, errorMessage) { page ->
            page.copy(annotations = snapshot.annotations, confidentialMarks = snapshot.confidentialMarks)
        }
    }

    private fun publishHistory() {
        localState.update { it.copy(canUndo = markupHistory.canUndo, canRedo = markupHistory.canRedo) }
    }

    /** Applies [transform] to the page's current row and saves it, keeping the home thumbnail in sync. */
    private fun updatePage(pageId: Long, errorMessage: String, transform: (Page) -> Page) {
        launchEdit(errorMessage) {
            val page = documentRepository.getPage(pageId) ?: return@launchEdit
            val updated = transform(page)
            if (updated == page) return@launchEdit
            savePage(updated)
        }
    }

    private suspend fun savePage(page: Page) {
        documentRepository.updatePage(page)
        refreshThumbnailIfFirst(page)
        touchDocument()
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
        const val MARKS_NEED_PRO = "Adding confidential marks needs Papr Pro."
    }
}

/** The limited features an export with [settings] uses - the password only counts when one is set, and images use none. */
internal fun meteredFeaturesFor(settings: ExportSettings): Set<MeteredFeature> = buildSet {
    if (settings.format == ExportFormat.IMAGES) return@buildSet
    if (settings.ocrEnabled) add(MeteredFeature.OCR_EXPORT)
    if (settings.passwordEnabled && settings.password.isNotBlank()) add(MeteredFeature.PASSWORD_EXPORT)
    if (settings.compressToTargetSize) add(MeteredFeature.COMPRESSED_EXPORT)
}

private fun Page.markupSnapshot() = MarkupSnapshot(annotations, confidentialMarks)

/**
 * The pages whose pageIndex has to change for them to read in [orderedIds]'s
 * order, renumbered 0..n-1. Ids that aren't pages any more are skipped, and
 * pages missing from [orderedIds] (added meanwhile) keep their order at the end.
 */
internal fun pagesInNewOrder(pages: List<Page>, orderedIds: List<Long>): List<Page> {
    val byId = pages.associateBy { it.id }
    val ordered = orderedIds.mapNotNull { byId[it] }
    val rest = pages.sortedBy { it.pageIndex }.filter { it.id !in orderedIds }
    return (ordered + rest).mapIndexedNotNull { index, page ->
        page.takeIf { it.pageIndex != index }?.copy(pageIndex = index)
    }
}
