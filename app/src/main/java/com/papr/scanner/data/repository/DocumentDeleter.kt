package com.papr.scanner.data.repository

import android.content.Context
import com.papr.scanner.core.FileUtils
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Deletes a document completely: its row (Room cascades the page rows), then its page/signature files and cached thumbnail. */
class DocumentDeleter(
    private val documentRepository: DocumentRepository,
    private val appContext: Context,
) {
    // Finishes once started: cancelled between the row and the files, the files would stay on
    // disk for good, with no row left to say they belong to anything.
    suspend fun delete(document: Document) {
        withContext(NonCancellable) {
            documentRepository.deleteDocument(document)
            withContext(Dispatchers.IO) {
                FileUtils.documentDir(appContext, document.id).deleteRecursively()
                FileUtils.thumbnailFile(appContext, document.id).delete()
            }
        }
    }
}
