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
        scope.launch { extractAll(pages) }
    }

    /**
     * Catches up on pages whose OCR never succeeded - e.g. scanned while the
     * text-recognition model was still downloading right after install, which
     * is exactly when a new user scans their first document.
     */
    fun extractMissingInBackground() {
        scope.launch { extractAll(documentRepository.getPagesMissingText()) }
    }

    private suspend fun extractAll(pages: List<Page>) {
        for (page in pages) {
            try {
                extractAndSave(page)
            } catch (e: TextRecognitionUnavailableException) {
                // Every remaining page would fail the same way; their text stays null,
                // so extractMissingInBackground picks them up on a later launch.
                Log.w("Papr", "Text recognition not ready yet; leaving ${pages.size} page(s) for later", e)
                return
            }
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
            // "" (not null) when nothing was found, so it isn't retried as "never indexed".
            documentRepository.updateExtractedText(page.id, page.imageUri, text.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TextRecognitionUnavailableException) {
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
