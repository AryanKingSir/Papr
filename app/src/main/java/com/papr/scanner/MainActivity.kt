package com.papr.scanner

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
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
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.theme.PaprTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaprTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PaprPlaceholder()
                }
            }
        }
        if (BuildConfig.DEBUG) {
            runPhase2SmokeTest()
        }
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
private fun PaprPlaceholder() {
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
