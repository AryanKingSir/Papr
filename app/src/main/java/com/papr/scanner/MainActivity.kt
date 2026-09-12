package com.papr.scanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.papr.scanner.core.FileUtils
import com.papr.scanner.di.AppContainer
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.navigation.PaprNavHost
import com.papr.scanner.presentation.navigation.Screen
import com.papr.scanner.presentation.theme.PaprTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var navigateToDocumentId by mutableStateOf<Long?>(null)

    /** Which document/page a completed scan result should be applied to. */
    private sealed interface ScanTarget {
        data object NewDocument : ScanTarget
        data class AppendPages(val documentId: Long) : ScanTarget
        data class ReplacePage(val documentId: Long, val pageId: Long, val pageIndex: Int) : ScanTarget
    }

    private var pendingScanTarget: ScanTarget = ScanTarget.NewDocument

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            handleScanResult(result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaprTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    LaunchedEffect(navigateToDocumentId) {
                        navigateToDocumentId?.let { id ->
                            navController.navigate(Screen.DocumentDetail.createRoute(id))
                            navigateToDocumentId = null
                        }
                    }

                    PaprNavHost(
                        navController = navController,
                        onScanClick = { startScan(ScanTarget.NewDocument) },
                        onAddPagesClick = { documentId -> startScan(ScanTarget.AppendPages(documentId)) },
                        onReplacePageClick = { documentId, pageId, pageIndex ->
                            startScan(ScanTarget.ReplacePage(documentId, pageId, pageIndex))
                        },
                    )
                }
            }
        }
    }

    private fun startScan(target: ScanTarget) {
        pendingScanTarget = target
        val container = (application as PaprApplication).container
        container.documentScannerManager.getStartScanIntent(this, singlePage = target is ScanTarget.ReplacePage)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { error ->
                Log.e("Papr", "Failed to start scanner", error)
            }
    }

    private fun handleScanResult(data: Intent?) {
        val container = (application as PaprApplication).container
        val pageUris = container.documentScannerManager.extractPageUris(data)
        if (pageUris.isEmpty()) return

        lifecycleScope.launch {
            try {
                when (val target = pendingScanTarget) {
                    is ScanTarget.NewDocument -> handleNewDocument(container, pageUris)
                    is ScanTarget.AppendPages -> handleAppendPages(container, target.documentId, pageUris)
                    is ScanTarget.ReplacePage -> handleReplacePage(container, target, pageUris)
                }
            } catch (e: Exception) {
                Log.e("Papr", "Failed to import scanned pages", e)
                Toast.makeText(this@MainActivity, "Couldn't save the scan. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun handleNewDocument(container: AppContainer, pageUris: List<Uri>) {
        val now = System.currentTimeMillis()
        val title = defaultTitle(now)

        val documentId = container.documentRepository.insertDocument(
            Document(
                id = 0,
                title = title,
                createdAt = now,
                updatedAt = now,
                pageCount = pageUris.size,
                thumbnailUri = "",
                pdfUri = null,
                folder = null,
            )
        )

        val pageFiles = container.documentScannerManager.importScannedPages(documentId, pageUris)
        val pages = pageFiles.mapIndexed { index, file ->
            Page(id = 0, documentId = documentId, pageIndex = index, imageUri = file.absolutePath, extractedText = null)
        }
        container.documentRepository.insertPages(pages)

        val thumbnail = FileUtils.generateThumbnail(this@MainActivity, documentId, pageFiles.first())
        container.documentRepository.updateDocument(
            Document(
                id = documentId,
                title = title,
                createdAt = now,
                updatedAt = now,
                pageCount = pageUris.size,
                thumbnailUri = thumbnail.absolutePath,
                pdfUri = null,
                folder = null,
            )
        )

        navigateToDocumentId = documentId
    }

    /** Scans more pages into a document that's already open, appending them after its existing pages. */
    private suspend fun handleAppendPages(container: AppContainer, documentId: Long, pageUris: List<Uri>) {
        val document = container.documentRepository.getDocumentById(documentId).first() ?: return
        val existingPages = container.documentRepository.getPagesForDocument(documentId).first()
        val startIndex = existingPages.size

        val pageFiles = container.documentScannerManager.importScannedPages(documentId, pageUris)
        val newPages = pageFiles.mapIndexed { offset, file ->
            Page(id = 0, documentId = documentId, pageIndex = startIndex + offset, imageUri = file.absolutePath, extractedText = null)
        }
        container.documentRepository.insertPages(newPages)
        container.documentRepository.updateDocument(
            document.copy(pageCount = existingPages.size + newPages.size, updatedAt = System.currentTimeMillis())
        )
    }

    /** Replaces a single existing page's image (e.g. re-scanning one that cropped wrong) in place. */
    private suspend fun handleReplacePage(container: AppContainer, target: ScanTarget.ReplacePage, pageUris: List<Uri>) {
        val document = container.documentRepository.getDocumentById(target.documentId).first() ?: return
        val existingPages = container.documentRepository.getPagesForDocument(target.documentId).first()
        val page = existingPages.firstOrNull { it.id == target.pageId } ?: return

        val newFile = container.documentScannerManager.importScannedPages(target.documentId, pageUris.take(1)).first()
        File(page.imageUri).delete()
        container.documentRepository.updatePage(page.copy(imageUri = newFile.absolutePath))

        if (target.pageIndex == 0) {
            FileUtils.generateThumbnail(this@MainActivity, target.documentId, newFile)
        }
        container.documentRepository.updateDocument(document.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun defaultTitle(timestamp: Long): String =
        "Scan " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
}
