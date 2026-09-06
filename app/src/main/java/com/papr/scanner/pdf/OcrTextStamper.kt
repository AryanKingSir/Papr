package com.papr.scanner.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class OcrTextElement(val text: String, val x: Float, val y: Float, val fontSize: Float)

/**
 * Runs on-device text recognition and returns each element's text with its
 * bounding-box position, in the same pixel space as the source bitmap.
 */
class OcrTextStamper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<OcrTextElement> {
        val text = recognizeText(bitmap)
        return text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
            val box = element.boundingBox ?: return@mapNotNull null
            OcrTextElement(
                text = element.text,
                x = box.left.toFloat(),
                y = box.bottom.toFloat(),
                fontSize = box.height().toFloat(),
            )
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text -> continuation.resume(text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
}
