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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentScannerManager(private val context: Context) {

    private val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    private val scanner by lazy { GmsDocumentScanning.getClient(options) }

    fun getStartScanIntent(activity: Activity): Task<IntentSender> =
        scanner.getStartScanIntent(activity)

    fun extractPageUris(resultIntent: Intent?): List<Uri> {
        val result = resultIntent?.let { GmsDocumentScanningResult.fromActivityResultIntent(it) }
        return result?.pages?.map { it.imageUri } ?: emptyList()
    }

    suspend fun importScannedPages(documentId: Long, pageUris: List<Uri>): List<File> =
        withContext(Dispatchers.IO) {
            val dir = FileUtils.documentDir(context, documentId)
            pageUris.mapIndexed { index, uri ->
                val destination = File(dir, "page_$index.jpg")
                FileUtils.copyContentUriToFile(context, uri, destination)
                destination
            }
        }
}
