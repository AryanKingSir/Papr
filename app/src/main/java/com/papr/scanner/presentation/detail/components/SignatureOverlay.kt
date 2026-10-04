package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.PageSignature
import com.papr.scanner.domain.model.clampedTo
import com.papr.scanner.pdf.fittedImageBounds
import com.papr.scanner.presentation.common.PageImage
import java.io.File
import kotlin.math.roundToInt

private val CONTROL_SIZE = 28.dp

/**
 * Draws a page's signature over the displayed page image, aligned to the
 * image's fitted/cropped bounds within this composable ([imageSize] is the
 * page image's pixel size; [crop] must match the page image's content scale).
 *
 * When [isPlacing], the signature is outlined and can be dragged to move it,
 * resized from its bottom-right handle (aspect ratio locked), or removed.
 * Changes are reported once per gesture, on release, not on every frame.
 * Otherwise, tapping it calls [onTap] (null makes it purely decorative).
 */
@Composable
fun SignatureOverlay(
    signature: PageSignature,
    imageSize: IntSize,
    isPlacing: Boolean,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    onTap: (() -> Unit)? = null,
    onPlacementChange: (x: Float, y: Float, width: Float) -> Unit = { _, _, _ -> },
    onRemove: () -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // The live placement while a gesture is in progress; reset whenever the
    // stored placement changes underneath it.
    var draft by remember(signature) { mutableStateOf(signature) }
    val currentOnPlacementChange by rememberUpdatedState(onPlacementChange)
    val pageAspect = imageSize.width / imageSize.height.toFloat()

    Box(modifier = modifier.onSizeChanged { containerSize = it }) {
        if (containerSize.width == 0 || containerSize.height == 0) return@Box
        val bounds = fittedImageBounds(
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            imageWidth = imageSize.width.toFloat(),
            imageHeight = imageSize.height.toFloat(),
            crop = crop,
        )
        val left = bounds.left + draft.x * bounds.width
        val top = bounds.top + draft.y * bounds.height
        val width = draft.width * bounds.width
        val height = width * draft.aspectRatio
        val controlRadiusPx = with(LocalDensity.current) { CONTROL_SIZE.toPx() } / 2f

        val gestures = when {
            // Keyed on signature too: each saved placement makes a new `draft` state, and a
            // gesture still holding the old one would save stale values over the last change.
            isPlacing -> Modifier.pointerInput(bounds, pageAspect, signature) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        draft = draft.copy(
                            x = draft.x + drag.x / bounds.width,
                            y = draft.y + drag.y / bounds.height,
                        ).clampedTo(pageAspect)
                    },
                    onDragEnd = { currentOnPlacementChange(draft.x, draft.y, draft.width) },
                )
            }
            onTap != null -> Modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
            else -> Modifier
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(LocalDensity.current) { width.toDp() }, with(LocalDensity.current) { height.toDp() })
                .then(if (isPlacing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                .then(gestures),
        ) {
            PageImage(
                file = File(signature.imagePath),
                contentDescription = "Signature",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isPlacing) {
            // Remove, on the top-right corner.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .offset { IntOffset((left + width - controlRadiusPx).roundToInt(), (top - controlRadiusPx).roundToInt()) }
                    .size(CONTROL_SIZE)
                    .clickable(onClick = onRemove),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove signature", modifier = Modifier.size(16.dp))
                }
            }
            // Resize, on the bottom-right corner: the top-left corner stays put.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .offset {
                        IntOffset((left + width - controlRadiusPx).roundToInt(), (top + height - controlRadiusPx).roundToInt())
                    }
                    .size(CONTROL_SIZE)
                    .pointerInput(bounds, pageAspect, signature) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                draft = draft.copy(width = draft.width + drag.x / bounds.width).clampedTo(pageAspect)
                            },
                            onDragEnd = { currentOnPlacementChange(draft.x, draft.y, draft.width) },
                        )
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = "Resize signature",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
