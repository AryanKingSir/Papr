package com.papr.scanner.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.papr.scanner.core.FileUtils
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentScannerManager(private val context: Context) {

    private val multiPageScanner by lazy { GmsDocumentScanning.getClient(buildOptions(pageLimit = null)) }

    // A page limit of 1 keeps "replace this page" from letting the user
    // accidentally scan in extra pages instead of just the one they meant to redo.
    private val singlePageScanner by lazy { GmsDocumentScanning.getClient(buildOptions(pageLimit = 1)) }

    private fun buildOptions(pageLimit: Int?) = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .apply { pageLimit?.let { setPageLimit(it) } }
        .build()

    fun getStartScanIntent(activity: Activity, singlePage: Boolean = false): Task<IntentSender> =
        (if (singlePage) singlePageScanner else multiPageScanner).getStartScanIntent(activity)

    /**
     * Google's scanner library copies every page it returns into the app's cache and never deletes
     * them, so each scan would leave a full-size copy of its pages behind. Deletes [pageUris]' copies
     * once they're imported (or failed to be); anything else is left alone.
     */
    suspend fun deleteScannerCopies(pageUris: List<Uri>) = withContext(Dispatchers.IO) {
        pageUris.mapNotNull { uri -> uri.path?.takeIf { uri.scheme == "file" } }
            .filter { FileUtils.isInside(it, scannerCacheDir()) }
            .forEach { File(it).delete() }
    }

    /**
     * Removes copies from scans that were never imported (the app killed before the result
     * arrived). Only old ones: a result Android redelivers after restoring the app still needs its own.
     */
    suspend fun deleteStaleScannerCopies(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        scannerCacheDir().listFiles()
            ?.filter { now - it.lastModified() > STALE_COPY_AGE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    private fun scannerCacheDir() = File(context.cacheDir, "mlkit_docscan_ui_client")

    fun extractPageUris(resultIntent: Intent?): List<Uri> {
        val result = resultIntent?.let { GmsDocumentScanningResult.fromActivityResultIntent(it) }
        return result?.pages?.map { it.imageUri } ?: emptyList()
    }

    // Each imported page gets a globally-unique file name (rather than one derived
    // from its position in this batch), so importing more pages into a document
    // that already has some - or replacing a single existing page - can never
    // collide with a file that's already on disk.
    suspend fun importScannedPages(documentId: Long, pageUris: List<Uri>): List<File> =
        withContext(Dispatchers.IO) {
            val dir = FileUtils.documentDir(context, documentId)
            val copied = mutableListOf<File>()
            try {
                pageUris.forEach { uri ->
                    val destination = File(dir, "page_${UUID.randomUUID()}.jpg")
                    copied += destination
                    FileUtils.copyContentUriToFile(context, uri, destination)
                }
                copied
            } catch (e: Throwable) {
                // No row points at these yet, so nothing else would ever delete them.
                copied.forEach { it.delete() }
                throw e
            }
        }

    private companion object {
        const val STALE_COPY_AGE_MS = 24L * 60 * 60 * 1000
    }
}
