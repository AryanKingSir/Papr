package com.papr.scanner

import android.app.Application
import com.papr.scanner.core.FileUtils
import com.papr.scanner.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PaprApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.pageTextExtractor.extractMissingInBackground()
        removeUnfinishedDocuments()
        removeAbandonedExportCopies()
    }

    /** An export cut short by the app being killed leaves its page copies behind; none can be running yet. */
    private fun removeAbandonedExportCopies() {
        container.applicationScope.launch(Dispatchers.IO) {
            FileUtils.exportSnapshotRoot(this@PaprApplication).deleteRecursively()
        }
    }

    /**
     * The app being killed mid-import or mid-merge leaves a document with no pages, which would
     * sit in the grid as a blank card for good. Only documents from before this launch are
     * checked, so a scan being saved right now is never touched.
     */
    private fun removeUnfinishedDocuments() {
        val launchedAt = System.currentTimeMillis()
        container.applicationScope.launch {
            container.documentRepository.getAllDocuments().first()
                .filter { it.createdAt < launchedAt && container.documentRepository.getPages(it.id).isEmpty() }
                .forEach { container.documentDeleter.delete(it) }
        }
    }
}
