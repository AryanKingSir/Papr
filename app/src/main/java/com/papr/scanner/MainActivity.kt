package com.papr.scanner

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.papr.scanner.domain.model.ThemeMode
import com.papr.scanner.presentation.common.GatedHapticFeedback
import com.papr.scanner.presentation.common.Haptic
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.common.PaprHaptics
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

    // Set from the tap until the scanner returns, so a double tap can't open it twice.
    private var scannerOpening = false

    // Read at the moment of each haptic, so switching the setting never recomposes the app.
    private val hapticsEnabled: () -> Boolean = { container.appSettings.value?.hapticsEnabled != false }

    // For feedback that comes from outside Compose (scan results, the scanner failing to open).
    private val activityHaptics by lazy { PaprHaptics(window.decorView, hapticsEnabled) }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scannerOpening = false
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
                        is ScanImportEvent.OpenDocument -> {
                            activityHaptics.perform(Haptic.CONFIRM)
                            navigateToDocumentId = event.documentId
                        }
                        ScanImportEvent.Failed -> {
                            activityHaptics.perform(Haptic.REJECT)
                            showToast("Couldn't save the scan. Please try again.")
                        }
                    }
                }
            }
        }

        setContent {
            // Nothing until the stored theme and tour state are known (a few ms), rather than a flash
            // of the wrong theme or of Home before the welcome slides.
            val settings by container.appSettings.collectAsState()
            val onboarding by container.onboardingState.collectAsState()
            val appSettings = settings
            val onboardingState = onboarding
            if (appSettings != null && onboardingState != null) {
                val darkTheme = when (appSettings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                // The status and navigation bar icons follow the app's theme, which can now differ from the phone's.
                LaunchedEffect(darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                        navigationBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { darkTheme },
                    )
                }
                // Fixed for this activity: finishing the slides later mustn't swap the graph's start under it.
                val startDestination = rememberSaveable {
                    if (onboardingState.welcomeSeen) Screen.Home.route else Screen.Welcome.route
                }
                PaprContent(darkTheme, appSettings.dynamicColor, startDestination)
            }
        }
    }

    @Composable
    private fun PaprContent(darkTheme: Boolean, dynamicColor: Boolean, startDestination: String) {
        val view = LocalView.current
        val systemHaptics = LocalHapticFeedback.current
        val haptics = remember(view) { PaprHaptics(view, hapticsEnabled) }
        val gatedHaptics = remember(systemHaptics) { GatedHapticFeedback(systemHaptics, hapticsEnabled) }
        PaprTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
            CompositionLocalProvider(LocalPaprHaptics provides haptics, LocalHapticFeedback provides gatedHaptics) {
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
                        startDestination = startDestination,
                        onScanClick = { startScan(ScanTarget.NewDocument) },
                        onAddPagesClick = { documentId -> startScan(ScanTarget.AppendPages(documentId)) },
                        onReplacePageClick = { documentId, pageId -> startScan(ScanTarget.ReplacePage(documentId, pageId)) },
                    )
                }
            }
        }
    }

    private fun startScan(target: ScanTarget) {
        if (scannerOpening) return
        scannerOpening = true
        pendingScanTarget = target
        container.documentScannerManager.getStartScanIntent(this, singlePage = target is ScanTarget.ReplacePage)
            .addOnSuccessListener { intentSender ->
                // The activity may have been recreated (rotation, theme change) while the scanner
                // loaded; this one's launcher is unregistered then, and launching it crashes.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    scannerOpening = false
                }
            }
            .addOnFailureListener { error ->
                scannerOpening = false
                Log.e("Papr", "Failed to start scanner", error)
                activityHaptics.perform(Haptic.REJECT)
                // The scanner is part of Google Play services, so an outdated or missing copy is the
                // usual cause - retrying alone won't help then.
                showToast("Couldn't open the scanner. Update Google Play services and try again.")
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
        // androidx.activity's own defaults for the 3-button navigation bar's scrim.
        val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
        const val KEY_SCAN_TARGET_TYPE = "pending_scan_target_type"
        const val KEY_DOCUMENT_ID = "pending_scan_document_id"
        const val KEY_PAGE_ID = "pending_scan_page_id"
        const val TYPE_NEW_DOCUMENT = "new_document"
        const val TYPE_APPEND_PAGES = "append_pages"
        const val TYPE_REPLACE_PAGE = "replace_page"
    }
}
