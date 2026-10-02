package com.papr.scanner

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.papr.scanner.presentation.navigation.PaprNavHost
import com.papr.scanner.presentation.navigation.Screen
import com.papr.scanner.presentation.theme.PaprTheme
import com.papr.scanner.scanner.ScanImportEvent
import com.papr.scanner.scanner.ScanTarget
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as PaprApplication).container

    private var navigateToDocumentId by mutableStateOf<Long?>(null)

    private var pendingScanTarget: ScanTarget = ScanTarget.NewDocument

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            container.scanImporter.import(pendingScanTarget, container.documentScannerManager.extractPageUris(result.data))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingScanTarget = restoreScanTarget(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.scanImporter.events.collect { event ->
                    when (event) {
                        is ScanImportEvent.OpenDocument -> navigateToDocumentId = event.documentId
                        ScanImportEvent.Failed -> showToast("Couldn't save the scan. Please try again.")
                    }
                }
            }
        }

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
                        onReplacePageClick = { documentId, pageId -> startScan(ScanTarget.ReplacePage(documentId, pageId)) },
                    )
                }
            }
        }
    }

    private fun startScan(target: ScanTarget) {
        pendingScanTarget = target
        container.documentScannerManager.getStartScanIntent(this, singlePage = target is ScanTarget.ReplacePage)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { error ->
                Log.e("Papr", "Failed to start scanner", error)
                showToast("Couldn't open the scanner. Please try again.")
            }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // The scanner runs as a separate activity, so the process can be killed
    // while it's open; the target has to survive that to apply the result.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        when (val target = pendingScanTarget) {
            is ScanTarget.NewDocument -> outState.putString(KEY_SCAN_TARGET_TYPE, TYPE_NEW_DOCUMENT)
            is ScanTarget.AppendPages -> {
                outState.putString(KEY_SCAN_TARGET_TYPE, TYPE_APPEND_PAGES)
                outState.putLong(KEY_DOCUMENT_ID, target.documentId)
            }
            is ScanTarget.ReplacePage -> {
                outState.putString(KEY_SCAN_TARGET_TYPE, TYPE_REPLACE_PAGE)
                outState.putLong(KEY_DOCUMENT_ID, target.documentId)
                outState.putLong(KEY_PAGE_ID, target.pageId)
            }
        }
    }

    private fun restoreScanTarget(savedInstanceState: Bundle?): ScanTarget {
        val bundle = savedInstanceState ?: return ScanTarget.NewDocument
        return when (bundle.getString(KEY_SCAN_TARGET_TYPE)) {
            TYPE_APPEND_PAGES -> ScanTarget.AppendPages(documentId = bundle.getLong(KEY_DOCUMENT_ID))
            TYPE_REPLACE_PAGE -> ScanTarget.ReplacePage(
                documentId = bundle.getLong(KEY_DOCUMENT_ID),
                pageId = bundle.getLong(KEY_PAGE_ID),
            )
            else -> ScanTarget.NewDocument
        }
    }

    private companion object {
        const val KEY_SCAN_TARGET_TYPE = "pending_scan_target_type"
        const val KEY_DOCUMENT_ID = "pending_scan_document_id"
        const val KEY_PAGE_ID = "pending_scan_page_id"
        const val TYPE_NEW_DOCUMENT = "new_document"
        const val TYPE_APPEND_PAGES = "append_pages"
        const val TYPE_REPLACE_PAGE = "replace_page"
    }
}
