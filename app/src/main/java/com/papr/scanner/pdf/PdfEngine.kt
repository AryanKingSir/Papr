package com.papr.scanner.pdf

import android.graphics.Bitmap
import com.papr.scanner.pdf.security.PdfEncryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SignaturePlacement(val bitmap: Bitmap, val pageIndex: Int)

/** [ownerPassword] defaults to [userPassword] when blank, per the Standard Security Handler. */
data class PasswordProtection(val userPassword: String, val ownerPassword: String = "")

data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val signature: SignaturePlacement? = null,
    val passwordProtection: PasswordProtection? = null,
    val targetSizeBytes: Long? = null,
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
    ): File = withContext(Dispatchers.IO) {
        val ocrElementsByPage = mutableMapOf<Int, List<OcrTextElement>>()

        bitmapPdfCompiler.compile(pageFiles, outputFile, options.targetSizeBytes) { canvas, bitmap, pageIndex, pageWidthPx, pageHeightPx ->
            if (options.ocrEnabled) {
                // Injected as raw PDF operators, bypassing Canvas entirely - so unlike
                // the signature below, this gets none of BitmapPdfCompiler's own
                // scale/translate for free and has to redo both by hand:
                //  - offset by the same centering translate the page's bitmap got,
                //    since OcrTextStamper's coordinates are relative to that bitmap;
                //  - flip Y: OcrTextElement.y comes from a top-down Android bounding
                //    box, but PDF space is bottom-up.
                val scale = BitmapPdfCompiler.POINTS_PER_PIXEL
                val offsetXPx = (pageWidthPx - bitmap.width) / 2f
                val offsetYPx = (pageHeightPx - bitmap.height) / 2f
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap).map { element ->
                    element.copy(
                        x = (element.x + offsetXPx) * scale,
                        y = (pageHeightPx - (element.y + offsetYPx)) * scale,
                        fontSize = element.fontSize * scale,
                    )
                }
            }
            options.signature?.let { placement ->
                if (placement.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, placement.bitmap, bitmap.width, bitmap.height)
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

        outputFile
    }
}
