package com.papr.scanner.pdf

import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.PageSignature
import com.papr.scanner.pdf.security.PdfEncryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [ownerPassword] defaults to [userPassword] when blank, per the Standard Security Handler. */
data class PasswordProtection(val userPassword: String, val ownerPassword: String = "")

data class PdfGenerationOptions(
    val ocrEnabled: Boolean = false,
    val signaturesByPageIndex: Map<Int, PageSignature> = emptyMap(),
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

        bitmapPdfCompiler.compile(
            pageFiles,
            outputFile,
            options.targetSizeBytes,
            preparePage = { canvas, bitmap, pageIndex ->
                // Drawn into the page image's own pixels rather than layered on top
                // as separate PDF objects: a mark can't be lifted off in a PDF editor
                // to reveal what's beneath, overlays shrink with a compression target,
                // and the mark's label font never has to be embedded in the file.
                // Signature first, so a confidential mark always stays on top.
                options.signaturesByPageIndex[pageIndex]?.let { signature ->
                    signatureStamper.draw(canvas, signature, bitmap.width, bitmap.height)
                }
                options.confidentialMarksByPageIndex[pageIndex]?.let { marks ->
                    confidentialMarkRenderer.draw(canvas, marks, bitmap.width, bitmap.height)
                }
            },
        ) { bitmap, pageIndex, placement ->
            if (options.ocrEnabled) {
                // Runs on the already-marked image, so text hidden by a confidential
                // mark is never recognized into the selectable text layer.
                // Injected as raw PDF operators, bypassing Canvas entirely - so
                // placement applies the page's fit transform by hand (and flips Y,
                // since PDF space is bottom-up while OcrTextStamper's boxes are top-down).
                ocrElementsByPage[pageIndex] = ocrTextStamper.recognize(bitmap).map { element ->
                    element.copy(
                        x = placement.toPdfX(element.x),
                        y = placement.toPdfY(element.y),
                        fontSize = placement.toPdfLength(element.fontSize),
                        width = placement.toPdfLength(element.width),
                    )
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
