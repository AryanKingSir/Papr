package com.papr.scanner

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.navigation.PaprNavHost
import com.papr.scanner.presentation.navigation.Screen
import com.papr.scanner.presentation.theme.PaprTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var navigateToDocumentId by mutableStateOf<Long?>(null)

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

                    PaprNavHost(navController = navController, onScanClick = ::startScan)
                }
            }
        }
    }

    private fun startScan() {
        val container = (application as PaprApplication).container
        container.documentScannerManager.getStartScanIntent(this)
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
    }

    private fun defaultTitle(timestamp: Long): String =
        "Scan " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
}
