package com.papr.scanner.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.papr.scanner.data.local.datastore.UserPreferencesRepository
import com.papr.scanner.data.local.db.PaprDatabase
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.data.repository.DocumentRepositoryImpl
import com.papr.scanner.pdf.BitmapPdfCompiler
import com.papr.scanner.pdf.InvisibleTextLayerInjector
import com.papr.scanner.pdf.OcrTextStamper
import com.papr.scanner.pdf.PdfEngine
import com.papr.scanner.pdf.SignatureStamper
import com.papr.scanner.pdf.security.PdfEncryptor
import com.papr.scanner.scanner.DocumentScannerManager

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_prefs")

class AppContainer(private val context: Context) {

    private val database: PaprDatabase by lazy {
        Room.databaseBuilder(context, PaprDatabase::class.java, PaprDatabase.NAME).build()
    }

    val documentRepository: DocumentRepository by lazy {
        DocumentRepositoryImpl(database.documentDao(), database.pageDao())
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
            InvisibleTextLayerInjector(),
            PdfEncryptor(),
        )
    }
}
