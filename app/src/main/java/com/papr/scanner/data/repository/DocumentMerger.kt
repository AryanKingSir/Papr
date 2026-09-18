package com.papr.scanner.data.repository

import android.content.Context
import com.papr.scanner.core.FileUtils
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Combines several existing documents' pages, in the given order, into one new persisted document. Originals are left untouched - pages are real file copies, not shared references. */
class DocumentMerger(
    private val documentRepository: DocumentRepository,
    private val appContext: Context,
) {
    suspend fun merge(documentIds: List<Long>, title: String): Long {
        val now = System.currentTimeMillis()
        val newDocumentId = documentRepository.insertDocument(
            Document(
                id = 0,
                title = title,
                createdAt = now,
                updatedAt = now,
                pageCount = 0,
                thumbnailUri = "",
                pdfUri = null,
                folder = null,
            )
        )

        val newDir = FileUtils.documentDir(appContext, newDocumentId)
        var nextPageIndex = 0
        val newPages = mutableListOf<Page>()
        for (documentId in documentIds) {
            val sourcePages = documentRepository.getPagesForDocument(documentId).first().sortedBy { it.pageIndex }
            for (page in sourcePages) {
                val destination = File(newDir, "page_${UUID.randomUUID()}.jpg")
                withContext(Dispatchers.IO) { File(page.imageUri).copyTo(destination) }
                newPages += Page(
                    id = 0,
                    documentId = newDocumentId,
                    pageIndex = nextPageIndex,
                    imageUri = destination.absolutePath,
                    extractedText = page.extractedText,
                )
                nextPageIndex++
            }
        }
        documentRepository.insertPages(newPages)

        val thumbnail = FileUtils.generateThumbnail(appContext, newDocumentId, File(newPages.first().imageUri))
        documentRepository.updateDocument(
            Document(
                id = newDocumentId,
                title = title,
                createdAt = now,
                updatedAt = now,
                pageCount = newPages.size,
                thumbnailUri = thumbnail.absolutePath,
                pdfUri = null,
                folder = null,
            )
        )

        return newDocumentId
    }
}
