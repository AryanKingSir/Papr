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
            pageUris.map { uri ->
                val destination = File(dir, "page_${UUID.randomUUID()}.jpg")
                FileUtils.copyContentUriToFile(context, uri, destination)
                destination
            }
        }
}
