package com.papr.scanner.pdf

import android.graphics.Bitmap
import com.papr.scanner.domain.model.ConfidentialMarkStroke
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
    val confidentialMarksByPageIndex: Map<Int, List<ConfidentialMarkStroke>> = emptyMap(),
    val passwordProtection: PasswordProtection? = null,
    val targetSizeBytes: Long? = null,
)

class PdfEngine(
    private val bitmapPdfCompiler: BitmapPdfCompiler,
    private val ocrTextStamper: OcrTextStamper,
    private val signatureStamper: SignatureStamper,
    private val confidentialMarkRenderer: ConfidentialMarkRenderer,
    private val invisibleTextLayerInjector: InvisibleTextLayerInjector,
    private val pdfEncryptor: PdfEncryptor,
) {

    suspend fun generatePdf(
        pageFiles: List<File>,
        outputFile: File,
        options: PdfGenerationOptions = PdfGenerationOptions(),
    ): File = withContext(Dispatchers.IO) {
        val ocrElementsByPage = mutableMapOf<Int, List<OcrTextElement>>()

        bitmapPdfCompiler.compile(pageFiles, outputFile, options.targetSizeBytes) { canvas, bitmap, pageIndex, placement ->
            if (options.ocrEnabled) {
                // Injected as raw PDF operators, bypassing Canvas entirely - so unlike
                // the overlays below, this doesn't get the page's fit transform for
                // free; placement applies it by hand (and flips Y, since PDF space
                // is bottom-up while OcrTextStamper's boxes are top-down).
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap).map { element ->
                    element.copy(
                        x = placement.toPdfX(element.x),
                        y = placement.toPdfY(element.y),
                        fontSize = placement.toPdfLength(element.fontSize),
                        width = placement.toPdfLength(element.width),
                    )
                }
            }
            options.signature?.let { signature ->
                if (signature.pageIndex == pageIndex) {
                    signatureStamper.stamp(canvas, signature.bitmap, bitmap.width, bitmap.height)
                }
            }
            options.confidentialMarksByPageIndex[pageIndex]?.let { marks ->
                confidentialMarkRenderer.draw(canvas, marks, bitmap.width, bitmap.height)
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
