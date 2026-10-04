package com.papr.scanner.data.local

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The saved signature's file, and when it was last written (so a replaced one is never shown from a stale cache). */
data class SavedSignature(val file: File, val lastModified: Long)

/**
 * The one signature kept for reuse. It's only ever copied into a document, never
 * referenced from one, so document folders stay self-contained. Settings and the
 * editor both watch [state], so a change made in one shows in the other.
 */
class SavedSignatureStore(context: Context, scope: CoroutineScope) {

    private val file = File(File(context.filesDir, "signature"), "saved_signature.png")

    private val _state = MutableStateFlow<SavedSignature?>(null)
    val state: StateFlow<SavedSignature?> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Saves [signature] (PNG, keeping its transparent background), replacing any saved one. */
    suspend fun save(signature: Bitmap) = write { out -> signature.compress(Bitmap.CompressFormat.PNG, 100, out) }

    /** Saves a copy of an already-written signature PNG, replacing any saved one. */
    suspend fun saveCopyOf(source: File) = write { out -> source.inputStream().use { it.copyTo(out) } }

    suspend fun delete() = withContext(Dispatchers.IO) {
        file.delete()
        refresh()
    }

    /** Copies the saved signature to [destination]; false if there's none to copy. */
    suspend fun copyTo(destination: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        file.copyTo(destination, overwrite = true)
        true
    }

    // Written beside the real file and renamed over it, so a failed write never leaves half a signature.
    private suspend fun write(writeTo: (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use(writeTo)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        refresh()
    }

    private fun refresh() {
        _state.value = file.takeIf { it.exists() }?.let { SavedSignature(it, it.lastModified()) }
    }
}
