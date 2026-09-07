package com.papr.scanner.presentation.detail.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun SignatureOverlayEditor(onSave: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    var strokes by remember { mutableStateOf(listOf<List<Offset>>()) }
    var currentStroke by remember { mutableStateOf(listOf<Offset>()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
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
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke = listOf(offset) },
                                onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                                onDragEnd = {
                                    strokes = strokes + listOf(currentStroke)
                                    currentStroke = emptyList()
                                },
                            )
                        },
                ) {
                    for (stroke in strokes + listOf(currentStroke)) {
                        for (i in 0 until stroke.size - 1) {
                            drawLine(
                                color = Color.Black,
                                start = stroke[i],
                                end = stroke[i + 1],
                                strokeWidth = 6f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
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
                            onClick = { onSave(renderSignatureBitmap(strokes, canvasSize)) },
                            enabled = strokes.isNotEmpty(),
                        ) { Text("Use signature") }
                    }
                }
            }
        }
    }
}

private fun renderSignatureBitmap(strokes: List<List<Offset>>, size: IntSize): Bitmap {
    val width = size.width.coerceAtLeast(1)
    val height = size.height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
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
