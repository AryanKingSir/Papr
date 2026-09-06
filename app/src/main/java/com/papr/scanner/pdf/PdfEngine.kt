package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import com.papr.scanner.pdf.security.PdfEncryptor
import java.io.File

data class SignaturePlacement(val bitmap: Bitmap, val pageIndex: Int, val rect: Rect)

/** [ownerPassword] defaults to [userPassword] when blank, per the Standard Security Handler. */
data class PasswordProtection(val userPassword: String, val ownerPassword: String = "")

data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val signature: SignaturePlacement? = null,
    val passwordProtection: PasswordProtection? = null,
)

class PdfEngine(
    private val bitmapPdfCompiler: BitmapPdfCompiler,
    private val ocrTextStamper: OcrTextStamper,
    private val signatureStamper: SignatureStamper,
    private val invisibleTextLayerInjector: InvisibleTextLayerInjector,
    private val pdfEncryptor: PdfEncryptor,
) {

    suspend fun generatePdf(
        pageFiles: List<File>,
        outputFile: File,
        options: PdfGenerationOptions = PdfGenerationOptions(),
    ): File {
        val ocrElementsByPage = mutableMapOf<Int, List<OcrTextElement>>()

        bitmapPdfCompiler.compile(pageFiles, outputFile) { canvas, bitmap, pageIndex ->
            if (options.ocrEnabled) {
                // Injected as raw PDF operators, bypassing Canvas's page-scale transform,
                // so bitmap-pixel coordinates must be scaled to PDF points explicitly here.
                val scale = BitmapPdfCompiler.POINTS_PER_PIXEL
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap).map { element ->
                    element.copy(x = element.x * scale, y = element.y * scale, fontSize = element.fontSize * scale)
                }
            }
            options.signature?.let { placement ->
                if (placement.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, placement.bitmap, placement.rect)
                }
            }
        }

        if (options.ocrEnabled) {
            invisibleTextLayerInjector.inject(outputFile, ocrElementsByPage)
        }

        // Must run last: encryption turns every other object's bytes into
        // ciphertext, so any later structural edit (OCR injection, etc.)
        // would corrupt the file.
        options.passwordProtection?.let { protection ->
            pdfEncryptor.encrypt(outputFile, protection.userPassword, protection.ownerPassword)
        }

        return outputFile
    }
}
