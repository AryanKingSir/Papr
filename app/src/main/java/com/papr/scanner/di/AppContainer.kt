package com.papr.scanner.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.papr.scanner.billing.BillingManager
import com.papr.scanner.billing.RealPlayBillingClient
import com.papr.scanner.core.Constants
import com.papr.scanner.data.local.datastore.UserPreferencesRepository
import com.papr.scanner.data.local.db.MIGRATION_1_2
import com.papr.scanner.data.local.db.MIGRATION_2_3
import com.papr.scanner.data.local.db.PaprDatabase
import com.papr.scanner.data.repository.DocumentDeleter
import com.papr.scanner.data.repository.DocumentMerger
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.data.repository.DocumentRepositoryImpl
import com.papr.scanner.pdf.BitmapPdfCompiler
import com.papr.scanner.pdf.ConfidentialMarkRenderer
import com.papr.scanner.pdf.InvisibleTextLayerInjector
import com.papr.scanner.pdf.OcrTextStamper
import com.papr.scanner.pdf.PageTextExtractor
import com.papr.scanner.pdf.PdfEngine
import com.papr.scanner.pdf.SignatureStamper
import com.papr.scanner.pdf.security.PdfEncryptor
import com.papr.scanner.scanner.DocumentScannerManager
import com.papr.scanner.scanner.ScanImporter
import com.papr.scanner.usage.UsageTracker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_prefs")

class AppContainer(private val context: Context) {

    val appContext: Context get() = context

    /**
     * For background work that must outlive any one screen or Activity instance
     * (scan import, OCR). SupervisorJob keeps one failure from cancelling its
     * siblings; the handler keeps an unexpected one from crashing the app.
     */
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("Papr", "Unhandled background error", throwable)
        }
    )

    private val database: PaprDatabase by lazy {
        Room.databaseBuilder(context, PaprDatabase::class.java, PaprDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    val documentRepository: DocumentRepository by lazy {
        DocumentRepositoryImpl(database.documentDao(), database.pageDao())
    }

    val documentDeleter: DocumentDeleter by lazy {
        DocumentDeleter(documentRepository, context)
    }

    val documentMerger: DocumentMerger by lazy {
        DocumentMerger(documentRepository, documentDeleter, context)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context.userPreferencesDataStore)
    }

    val documentScannerManager: DocumentScannerManager by lazy {
        DocumentScannerManager(context)
    }

    val pdfEngine: PdfEngine by lazy {
        PdfEngine(
            BitmapPdfCompiler(),
            OcrTextStamper(),
            SignatureStamper(),
            ConfidentialMarkRenderer(),
            InvisibleTextLayerInjector(),
            PdfEncryptor(),
        )
    }

    val pageTextExtractor: PageTextExtractor by lazy {
        PageTextExtractor(OcrTextStamper(), documentRepository, applicationScope)
    }

    val scanImporter: ScanImporter by lazy {
        ScanImporter(context, documentRepository, documentScannerManager, documentDeleter, pageTextExtractor, applicationScope)
    }

    val billingManager: BillingManager by lazy {
        BillingManager(RealPlayBillingClient(context), userPreferencesRepository, Constants.PRO_PRODUCT_ID)
    }

    val usageTracker: UsageTracker by lazy {
        UsageTracker(context.userPreferencesDataStore, userPreferencesRepository.isProUnlocked)
    }
}
