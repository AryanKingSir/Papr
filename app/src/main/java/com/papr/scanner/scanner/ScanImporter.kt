package com.papr.scanner.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentDeleter
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.pdf.PageTextExtractor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanImportEvent {
    /** A new document was saved and should be opened. */
    data class OpenDocument(val documentId: Long) : ScanImportEvent
    data object Failed : ScanImportEvent
}

/**
 * Saves scanner results into documents. Runs on the app-lifetime [scope]
 * rather than the Activity's, so rotating or backgrounding mid-save can't
 * cancel it halfway and leave a half-built document behind.
 */
class ScanImporter(
    private val appContext: Context,
    private val documentRepository: DocumentRepository,
    private val documentScannerManager: DocumentScannerManager,
    private val documentDeleter: DocumentDeleter,
    private val pageTextExtractor: PageTextExtractor,
    private val scope: CoroutineScope,
) {
    // Buffered and single-consumer: an outcome produced while the Activity is
    // being recreated waits for the new one instead of being dropped, and is
    // delivered exactly once.
    private val _events = Channel<ScanImportEvent>(Channel.BUFFERED)
    val events: Flow<ScanImportEvent> = _events.receiveAsFlow()

    fun import(target: ScanTarget, pageUris: List<Uri>) {
        if (pageUris.isEmpty()) return
        scope.launch {
            try {
                when (target) {
                    is ScanTarget.NewDocument -> {
                        val documentId = importNewDocument(pageUris)
                        _events.send(ScanImportEvent.OpenDocument(documentId))
                    }
                    is ScanTarget.AppendPages -> appendPages(target.documentId, pageUris)
                    is ScanTarget.ReplacePage -> replacePage(target, pageUris.first())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Papr", "Failed to import scanned pages", e)
                _events.send(ScanImportEvent.Failed)
            } finally {
                withContext(NonCancellable) { documentScannerManager.deleteScannerCopies(pageUris) }
            }
        }
    }

    private suspend fun importNewDocument(pageUris: List<Uri>): Long {
        val now = System.currentTimeMillis()
        val document = Document(
            id = 0,
            title = defaultTitle(now),
            createdAt = now,
            updatedAt = now,
            pageCount = pageUris.size,
            thumbnailUri = "",
            pdfUri = null,
            folder = null,
        )
        val documentId = documentRepository.insertDocument(document)
        try {
            val pageFiles = documentScannerManager.importScannedPages(documentId, pageUris)
            val pages = pageFiles.mapIndexed { index, file ->
                Page(id = 0, documentId = documentId, pageIndex = index, imageUri = file.absolutePath, extractedText = null)
            }
            val pageIds = documentRepository.insertPages(pages)
            pageTextExtractor.extractInBackground(pages.zip(pageIds) { page, id -> page.copy(id = id) })
            val thumbnail = FileUtils.generateThumbnail(appContext, pages.first())
            // Re-read: once its pages exist the document shows on Home, and could be renamed meanwhile.
            val saved = documentRepository.getDocument(documentId) ?: document.copy(id = documentId)
            documentRepository.updateDocument(saved.copy(thumbnailUri = thumbnail.absolutePath))
            return documentId
        } catch (e: Throwable) {
            // Never leave an empty, thumbnail-less document in the grid.
            withContext(NonCancellable) {
                runCatching { documentDeleter.delete(document.copy(id = documentId)) }
            }
            throw e
        }
    }

    /** Scans more pages into an existing document, after its current pages. */
    private suspend fun appendPages(documentId: Long, pageUris: List<Uri>) {
        documentRepository.getDocument(documentId) ?: return
        val pageFiles = documentScannerManager.importScannedPages(documentId, pageUris)
        try {
            val startIndex = documentRepository.getPages(documentId).size
            val newPages = pageFiles.mapIndexed { offset, file ->
                Page(id = 0, documentId = documentId, pageIndex = startIndex + offset, imageUri = file.absolutePath, extractedText = null)
            }
            val newPageIds = documentRepository.insertPages(newPages)
            pageTextExtractor.extractInBackground(newPages.zip(newPageIds) { page, id -> page.copy(id = id) })
            // Re-read rather than reuse the earlier read, so a concurrent edit isn't overwritten.
            val document = documentRepository.getDocument(documentId) ?: return
            documentRepository.updateDocument(
                document.copy(pageCount = documentRepository.getPages(documentId).size, updatedAt = System.currentTimeMillis())
            )
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { pageFiles.forEach { it.delete() } }
            throw e
        }
    }

    /**
     * Replaces one page's image (e.g. re-scanning one that cropped wrong). The new image starts
     * clean: marks, markup and the signature were placed on the old framing, and on a rescan they
     * could land over different text - a confidential mark included.
     */
    private suspend fun replacePage(target: ScanTarget.ReplacePage, pageUri: Uri) {
        documentRepository.getPage(target.pageId) ?: return
        val newFile = documentScannerManager.importScannedPages(target.documentId, listOf(pageUri)).first()
        // Re-read after the (slow) file copy, so edits made meanwhile aren't overwritten.
        val page = documentRepository.getPage(target.pageId)
        if (page == null) {
            withContext(Dispatchers.IO) { newFile.delete() }
            return
        }
        val updatedPage = page.copy(
            imageUri = newFile.absolutePath,
            extractedText = null,
            confidentialMarks = emptyList(),
            annotations = emptyList(),
            signature = null,
        )
        // Point the row at the new image *before* deleting the old one, so a
        // failure in between can never leave a page whose image is gone.
        documentRepository.updatePage(updatedPage)
        withContext(Dispatchers.IO) {
            File(page.imageUri).delete()
            page.signature?.let { File(it.imagePath).delete() }
        }
        pageTextExtractor.extractInBackground(listOf(updatedPage))

        if (updatedPage.pageIndex == 0) {
            FileUtils.generateThumbnail(appContext, updatedPage)
        }
        documentRepository.getDocument(target.documentId)?.let { document ->
            documentRepository.updateDocument(document.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private fun defaultTitle(timestamp: Long): String =
        "Scan " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
}
