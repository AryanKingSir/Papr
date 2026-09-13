package com.papr.scanner.data.repository

import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<Document>>
    fun getDocumentById(id: Long): Flow<Document?>
    fun getPagesForDocument(documentId: Long): Flow<List<Page>>
    fun getAllPages(): Flow<List<Page>>

    suspend fun insertDocument(document: Document): Long
    suspend fun updateDocument(document: Document)
    suspend fun deleteDocument(document: Document)

    suspend fun insertPage(page: Page): Long
    suspend fun insertPages(pages: List<Page>): List<Long>
    suspend fun updatePage(page: Page)
    suspend fun deletePage(page: Page)
}
