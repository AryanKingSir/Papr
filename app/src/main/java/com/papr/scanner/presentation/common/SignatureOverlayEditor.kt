package com.papr.scanner.presentation.common

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.createBitmap

@Composable
fun SignatureOverlayEditor(
    onSave: (signature: Bitmap, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
    // Off where remembering isn't the choice to make here: in Settings (where it always is) or
    // in the editor once a signature is saved (replacing it only happens in Settings).
    showRememberOption: Boolean = true,
) {
    var strokes by rememberSaveable(stateSaver = StrokesSaver) { mutableStateOf(listOf<List<Offset>>()) }
    var currentStroke by remember { mutableStateOf(listOf<Offset>()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // On by default: drawing the same signature again on every page is the chore this saves.
    var rememberSignature by rememberSaveable { mutableStateOf(showRememberOption) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            // Scrolls so the buttons stay reachable in landscape; drawing on the pad isn't
            // affected, since the pad takes its own drags.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("Draw your signature", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Sign with your finger below.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectStrokes(
                                drawDots = true,
                                onStroke = { currentStroke = it },
                                onEnd = {
                                    strokes = strokes + listOf(currentStroke)
                                    currentStroke = emptyList()
                                },
                                onCancel = { currentStroke = emptyList() },
                            )
                        },
                ) {
                    for (stroke in strokes + listOf(currentStroke)) {
                        for (i in 0 until stroke.size - 1) {
                            drawLine(
                                color = Color.Black,
                                start = stroke[i],
                                end = stroke[i + 1],
                                strokeWidth = STROKE_WIDTH_PX,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                if (showRememberOption) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .toggleable(value = rememberSignature, role = Role.Checkbox, onValueChange = { rememberSignature = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = rememberSignature, onCheckedChange = null)
                        Text(
                            "Remember this signature",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = {
                            strokes = emptyList()
                            currentStroke = emptyList()
                        },
                        enabled = strokes.isNotEmpty(),
                    ) { Text("Clear") }
                    Row {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = { renderSignatureBitmap(strokes, canvasSize)?.let { onSave(it, rememberSignature) } },
                            enabled = strokes.isNotEmpty(),
                        ) { Text("Use signature") }
                    }
                }
            }
        }
    }
}

/** Renders just the inked area (see [signatureCropRect]) onto a transparent bitmap; null if nothing was drawn. */
private fun renderSignatureBitmap(strokes: List<List<Offset>>, size: IntSize): Bitmap? {
    val crop = signatureCropRect(strokes, padding = STROKE_WIDTH_PX, canvasWidth = size.width, canvasHeight = size.height)
        ?: return null
    val bitmap = createBitmap(crop.width, crop.height)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.translate(-crop.left.toFloat(), -crop.top.toFloat())
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        strokeCap = android.graphics.Paint.Cap.ROUND
        isAntiAlias = true
    }
    for (stroke in strokes) {
        for (i in 0 until stroke.size - 1) {
            canvas.drawLine(stroke[i].x, stroke[i].y, stroke[i + 1].x, stroke[i + 1].y, paint)
        }
    }
    return bitmap
}

private const val STROKE_WIDTH_PX = 6f

/** Keeps a half-drawn signature through a rotation: each stroke as x, y, x, y... */
private val StrokesSaver = Saver<List<List<Offset>>, ArrayList<FloatArray>>(
    save = { strokes -> ArrayList(strokes.map { stroke -> stroke.flatMap { listOf(it.x, it.y) }.toFloatArray() }) },
    restore = { saved -> saved.map { flat -> flat.toList().chunked(2) { (x, y) -> Offset(x, y) } } },
)
