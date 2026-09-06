package com.papr.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.pdf.PdfGenerationOptions
import com.papr.scanner.pdf.SignaturePlacement
import com.papr.scanner.presentation.theme.PaprTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            handleDebugScanResult(result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaprTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PaprPlaceholder(
                        showDebugScanButton = BuildConfig.DEBUG,
                        onDebugScanClick = ::startDebugScan,
                    )
                }
            }
        }
        if (BuildConfig.DEBUG) {
            runPhase2SmokeTest()
        }
    }

    /**
     * Phase 3a verification only: drives the real GMS scanner UI, compiles the
     * captured pages into a PDF via PdfEngine, and hands it off through a Share
     * Sheet so the output can be checked in an external PDF viewer. Removed once
     * Phase 5 wires up the real scan/detail UI.
     */
    private fun startDebugScan() {
        val container = (application as PaprApplication).container
        container.documentScannerManager.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { error ->
                Log.e("PaprPhase3aSmoke", "Failed to start scanner", error)
            }
    }

    private fun handleDebugScanResult(data: Intent?) {
        val container = (application as PaprApplication).container
        val pageUris = container.documentScannerManager.extractPageUris(data)
        if (pageUris.isEmpty()) return

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val documentId = container.documentRepository.insertDocument(
                Document(
                    id = 0,
                    title = "Phase 3a Debug Scan",
                    createdAt = now,
                    updatedAt = now,
                    pageCount = pageUris.size,
                    thumbnailUri = "",
                    pdfUri = null,
                    folder = null,
                )
            )

            val pageFiles = container.documentScannerManager.importScannedPages(documentId, pageUris)
            val outputFile = File(FileUtils.documentDir(this@MainActivity, documentId), "document.pdf")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pageFiles.first().absolutePath, bounds)
            val signatureWidth = bounds.outWidth / 3
            val signatureHeight = bounds.outHeight / 8
            val signatureRect = Rect(
                bounds.outWidth - signatureWidth - 40,
                bounds.outHeight - signatureHeight - 40,
                bounds.outWidth - 40,
                bounds.outHeight - 40,
            )

            container.pdfEngine.generatePdf(
                pageFiles,
                outputFile,
                PdfGenerationOptions(
                    ocrEnabled = true,
                    signature = SignaturePlacement(
                        bitmap = createDebugSignatureBitmap(signatureWidth, signatureHeight),
                        pageIndex = 0,
                        rect = signatureRect,
                    ),
                ),
            )
            Log.d(
                "PaprPhase3bSmoke",
                "Generated PDF (OCR + signature) at ${outputFile.absolutePath}, size=${outputFile.length()} bytes",
            )

            val shareUri = FileUtils.getShareableUri(this@MainActivity, outputFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share scanned PDF"))
        }
    }

    /** Phase 3b verification only: synthesizes a squiggly stroke standing in for a real signature. */
    private fun createDebugSignatureBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = height / 12f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val path = Path().apply {
            moveTo(width * 0.05f, height * 0.7f)
            cubicTo(width * 0.2f, height * 0.1f, width * 0.3f, height * 0.9f, width * 0.5f, height * 0.4f)
            cubicTo(width * 0.65f, height * 0.05f, width * 0.8f, height * 0.6f, width * 0.95f, height * 0.3f)
        }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    /**
     * Phase 2 verification only: round-trips Room + DataStore so both can be
     * confirmed via Logcat before any real UI consumes them. Removed once
     * Phase 5 wires up real ViewModels/screens.
     */
    private fun runPhase2SmokeTest() {
        val container = (application as PaprApplication).container
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val documentId = container.documentRepository.insertDocument(
                Document(
                    id = 0,
                    title = "Debug Document",
                    createdAt = now,
                    updatedAt = now,
                    pageCount = 1,
                    thumbnailUri = "",
                    pdfUri = null,
                    folder = null,
                )
            )
            container.documentRepository.insertPage(
                Page(
                    id = 0,
                    documentId = documentId,
                    pageIndex = 0,
                    imageUri = "debug://page0",
                    extractedText = null,
                )
            )
            val documents = container.documentRepository.getAllDocuments().first()
            Log.d("PaprPhase2Smoke", "Room round-trip: $documents")

            container.userPreferencesRepository.setProUnlocked(true)
            val isPro = container.userPreferencesRepository.isProUnlocked.first()
            Log.d("PaprPhase2Smoke", "DataStore round-trip: isProUnlocked=$isPro")
        }
    }
}

@Composable
private fun PaprPlaceholder(
    showDebugScanButton: Boolean = false,
    onDebugScanClick: () -> Unit = {},
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.DocumentScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Papr",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Offline document scanner",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (showDebugScanButton) {
                Button(onClick = onDebugScanClick) {
                    Text("Scan (debug)")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PaprPlaceholderPreview() {
    PaprTheme {
        PaprPlaceholder()
    }
}
