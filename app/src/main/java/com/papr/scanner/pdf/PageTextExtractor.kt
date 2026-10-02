package com.papr.scanner.pdf

import android.util.Log
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Page
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Runs OCR on a page's image right after it's scanned, storing the plain text for search. */
class PageTextExtractor(
    private val ocrTextStamper: OcrTextStamper,
    private val documentRepository: DocumentRepository,
    // App-lifetime scope: OCR must outlive whichever screen triggered it (and
    // survive Activity recreation), and must never crash the app if it fails.
    private val scope: CoroutineScope,
) {
    /** Fires OCR for [pages] in the background, without holding up the scan/save flow that called it. */
    fun extractInBackground(pages: List<Page>) {
        scope.launch {
            pages.forEach { page -> extractAndSave(page) }
        }
    }

    private suspend fun extractAndSave(page: Page) {
        try {
            val file = File(page.imageUri)
            // Deleted or replaced before OCR got to it - nothing left to index.
            if (!file.exists()) return
            val bitmap = FileUtils.decodeDownsampled(file, MAX_DIMENSION)
            val text = try {
                ocrTextStamper.recognizePlainText(bitmap)
            } finally {
                bitmap.recycle()
            }
            documentRepository.updateExtractedText(page.id, page.imageUri, text.ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Search just won't find this page's content; everything else is unaffected.
            Log.w("Papr", "Text extraction failed for page ${page.id}", e)
        }
    }

    private companion object {
        const val MAX_DIMENSION = 3000
    }
}
