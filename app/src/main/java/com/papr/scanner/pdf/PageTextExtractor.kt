package com.papr.scanner.pdf

import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Page
import java.io.File

/** Runs OCR on a page's image right after it's scanned, storing the plain text for future search. */
class PageTextExtractor(
    private val ocrTextStamper: OcrTextStamper,
    private val documentRepository: DocumentRepository,
) {
    suspend fun extractAndSave(page: Page) {
        val bitmap = FileUtils.decodeDownsampled(File(page.imageUri), MAX_DIMENSION)
        val text = try {
            ocrTextStamper.recognizePlainText(bitmap)
        } finally {
            bitmap.recycle()
        }
        documentRepository.updatePage(page.copy(extractedText = text.ifBlank { null }))
    }

    private companion object {
        const val MAX_DIMENSION = 3000
    }
}
