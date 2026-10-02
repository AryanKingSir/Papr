package com.papr.scanner.data.repository

import android.content.Context
import com.papr.scanner.core.FileUtils
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Combines several existing documents' pages, in the given order, into one new
 * persisted document. Originals are left untouched - pages (and their
 * signatures) are real file copies, not shared references, and keep their
 * confidential marks so a merged copy of a redacted page stays redacted.
 */
class DocumentMerger(
    private val documentRepository: DocumentRepository,
    private val documentDeleter: DocumentDeleter,
    private val appContext: Context,
) {
    suspend fun merge(documentIds: List<Long>, title: String): Long {
        val now = System.currentTimeMillis()
        val document = Document(
            id = 0,
            title = title,
            createdAt = now,
            updatedAt = now,
            pageCount = 0,
            thumbnailUri = "",
            pdfUri = null,
            folder = null,
        )
        val newDocumentId = documentRepository.insertDocument(document)
        try {
            val newDir = FileUtils.documentDir(appContext, newDocumentId)
            val newPages = withContext(Dispatchers.IO) {
                documentIds
                    .flatMap { documentRepository.getPages(it) }
                    .mapIndexed { index, page -> copyPage(page, newDocumentId, newDir, index) }
            }
            check(newPages.isNotEmpty()) { "Nothing to merge" }
            documentRepository.insertPages(newPages)

            val thumbnail = FileUtils.generateThumbnail(appContext, newPages.first())
            documentRepository.updateDocument(
                document.copy(id = newDocumentId, pageCount = newPages.size, thumbnailUri = thumbnail.absolutePath)
            )
            return newDocumentId
        } catch (e: Throwable) {
            // Failed or cancelled partway: never leave a half-merged document behind.
            withContext(NonCancellable) {
                runCatching { documentDeleter.delete(document.copy(id = newDocumentId)) }
            }
            throw e
        }
    }

    private fun copyPage(page: Page, newDocumentId: Long, newDir: File, newIndex: Int): Page {
        val image = File(newDir, "page_${UUID.randomUUID()}.jpg")
        File(page.imageUri).copyTo(image)
        val signature = page.signature?.let { signature ->
            val signatureFile = File(newDir, "signature_${UUID.randomUUID()}.png")
            File(signature.imagePath).copyTo(signatureFile)
            signature.copy(imagePath = signatureFile.absolutePath)
        }
        return page.copy(
            id = 0,
            documentId = newDocumentId,
            pageIndex = newIndex,
            imageUri = image.absolutePath,
            signature = signature,
        )
    }
}
