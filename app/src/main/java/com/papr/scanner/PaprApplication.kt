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
        // Checks the purchase with Google Play once per process, not only when the paywall opens:
        // a payment that went through later gets acknowledged before Play's three-day auto-refund,
        // and a refunded Pro is turned off. Here rather than in MainActivity, whose saved state
        // can't tell a rotation from Android restoring the app after killing it in the background.
        container.applicationScope.launch { container.billingManager.connect() }
        removeUnfinishedDocuments()
        removeAbandonedExportCopies()
        container.applicationScope.launch { container.documentScannerManager.deleteStaleScannerCopies() }
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
