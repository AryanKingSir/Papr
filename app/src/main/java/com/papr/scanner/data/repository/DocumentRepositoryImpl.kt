package com.papr.scanner.data.repository

import com.papr.scanner.data.local.db.DocumentDao
import com.papr.scanner.data.local.db.DocumentEntity
import com.papr.scanner.data.local.db.PageDao
import com.papr.scanner.data.local.db.PageEntity
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DocumentRepositoryImpl(
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
) : DocumentRepository {

    override fun getAllDocuments(): Flow<List<Document>> =
        documentDao.getAll().map { entities -> entities.map { it.toDomain() } }

    override fun getDocumentById(id: Long): Flow<Document?> =
        documentDao.getById(id).map { it?.toDomain() }

    override fun getPagesForDocument(documentId: Long): Flow<List<Page>> =
        pageDao.getForDocument(documentId).map { entities -> entities.map { it.toDomain() } }

    override fun getAllPages(): Flow<List<Page>> =
        pageDao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun insertDocument(document: Document): Long =
        documentDao.insert(document.toEntity())

    override suspend fun updateDocument(document: Document) =
        documentDao.update(document.toEntity())

    override suspend fun deleteDocument(document: Document) =
        documentDao.delete(document.toEntity())

    override suspend fun insertPage(page: Page): Long =
        pageDao.insert(page.toEntity())

    override suspend fun insertPages(pages: List<Page>): List<Long> =
        pageDao.insertAll(pages.map { it.toEntity() })

    override suspend fun updatePage(page: Page) =
        pageDao.update(page.toEntity())

    override suspend fun deletePage(page: Page) =
        pageDao.delete(page.toEntity())
}

private fun DocumentEntity.toDomain() = Document(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pageCount = pageCount,
    thumbnailUri = thumbnailUri,
    pdfUri = pdfUri,
    folder = folder,
)

private fun Document.toEntity() = DocumentEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pageCount = pageCount,
    thumbnailUri = thumbnailUri,
    pdfUri = pdfUri,
    folder = folder,
)

private fun PageEntity.toDomain() = Page(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    imageUri = imageUri,
    extractedText = extractedText,
)

private fun Page.toEntity() = PageEntity(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    imageUri = imageUri,
    extractedText = extractedText,
)
