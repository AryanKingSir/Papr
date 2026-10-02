package com.papr.scanner.data.repository

import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<Document>>
    fun getDocumentById(id: Long): Flow<Document?>
    fun getPagesForDocument(documentId: Long): Flow<List<Page>>
    fun getAllPages(): Flow<List<Page>>

    // One-shot reads of the current rows, for edits that must start from what's
    // actually stored rather than a possibly-stale snapshot the UI is holding.
    suspend fun getDocument(id: Long): Document?
    suspend fun getPage(id: Long): Page?
    suspend fun getPages(documentId: Long): List<Page>
    suspend fun getPagesMissingText(): List<Page>

    suspend fun insertDocument(document: Document): Long
    suspend fun updateDocument(document: Document)
    suspend fun deleteDocument(document: Document)

    suspend fun insertPage(page: Page): Long
    suspend fun insertPages(pages: List<Page>): List<Long>
    suspend fun updatePage(page: Page)
    suspend fun deletePage(page: Page)

    /** Stores OCR text for a page, but only if it still shows [imageUri] - see PageDao.updateExtractedText. */
    suspend fun updateExtractedText(pageId: Long, imageUri: String, text: String?)
}
