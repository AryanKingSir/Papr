package com.papr.scanner.presentation.detail.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.Page
import com.papr.scanner.domain.model.Tip
import com.papr.scanner.presentation.common.Haptic
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.common.MainToolbar
import com.papr.scanner.presentation.common.SelectionCheck
import com.papr.scanner.presentation.common.TipCard
import com.papr.scanner.presentation.common.ToolButton
import com.papr.scanner.presentation.common.rememberTip
import com.papr.scanner.presentation.detail.idsWithPageMoved
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * Every page of the document in a grid. Hold and drag a page to move it; or
 * Select pages to rotate or delete them together. Tapping a page (when not
 * selecting) opens it in the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizePagesScreen(
    pages: List<Page>,
    revision: Int,
    onReorder: (orderedIds: List<Long>) -> Unit,
    onRotate: (Set<Long>) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onOpenPage: (index: Int) -> Unit,
    onDone: () -> Unit,
) {
    // A local copy that follows the finger while dragging; saved once, when the page is dropped.
    var order by remember { mutableStateOf(pages) }
    var isDragging by remember { mutableStateOf(false) }
    val haptics = LocalPaprHaptics.current
    val dragTip = rememberTip(Tip.ORGANIZE_DRAG)
    LaunchedEffect(pages) { if (!isDragging) order = pages }

    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = PageIdsSaver) { mutableStateOf(setOf<Long>()) }
    var pendingDelete by rememberSaveable { mutableStateOf(false) }
    // Pages deleted elsewhere (or by Delete here) drop out of the selection.
    val selection = selected.filter { id -> pages.any { it.id == id } }.toSet()
    val endSelection = {
        selecting = false
        selected = emptySet()
    }

    BackHandler { if (selecting) endSelection() else onDone() }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.perform(Haptic.TICK)
    }

    fun movePage(index: Int, step: Int): Boolean {
        val ids = idsWithPageMoved(order.map { it.id }, index, step)
        if (ids == order.map { it.id }) return false
        order = ids.mapNotNull { id -> order.firstOrNull { it.id == id } }
        onReorder(ids)
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selection.size} selected" else "Organize pages") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = endSelection) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
                    } else {
                        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { selected = pages.map { it.id }.toSet() }) { Text("Select all") }
                    } else {
                        TextButton(onClick = { selecting = true }) { Text("Select") }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                // Edge to edge: the bar's colour runs under the navigation bar, its buttons sit above it.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(modifier = Modifier.navigationBarsPadding()) {
                    MainToolbar(vertical = false) { weight ->
                        ToolButton(
                            Icons.AutoMirrored.Outlined.RotateRight,
                            "Rotate",
                            { onRotate(selection) },
                            weight,
                            enabled = selection.isNotEmpty(),
                        )
                        ToolButton(
                            Icons.Outlined.Delete,
                            "Delete",
                            { pendingDelete = true },
                            weight,
                            enabled = selection.isNotEmpty(),
                        )
                    }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (selecting) {
                Text(
                    text = "Tap pages to select them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                TipCard(tip = dragTip, text = "Hold a page, then drag it to move it.")
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(order, key = { _, page -> page.id }) { index, page ->
                    ReorderableItem(reorderState, key = page.id) { dragging ->
                        val isSelected = page.id in selection
                        // The held page lifts towards the finger and the rest step back, so it's clear what's moving.
                        val scale by animateFloatAsState(if (dragging) 1.08f else 1f, label = "lift")
                        val alpha by animateFloatAsState(if (isDragging && !dragging) 0.6f else 1f, label = "dim")
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(6.dp)
                                .graphicsLayer { this.alpha = alpha }
                                .longPressDraggableHandle(
                                    enabled = !selecting,
                                    onDragStarted = {
                                        isDragging = true
                                        haptics.perform(Haptic.DRAG_START)
                                        dragTip.dismiss()
                                    },
                                    onDragStopped = {
                                        isDragging = false
                                        val orderedIds = order.map { it.id }
                                        // Only a page that actually moved gets the "done" feel.
                                        if (orderedIds != pages.map { it.id }) haptics.perform(Haptic.CONFIRM)
                                        onReorder(orderedIds)
                                    },
                                ),
                        ) {
                            // Re-read in-place rewritten (rotated) page files; see PagePager.
                            key(revision) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(PAGE_ASPECT)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .shadow(if (dragging) 12.dp else 0.dp, RoundedCornerShape(8.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(
                                            BorderStroke(
                                                width = if (isSelected || dragging) 3.dp else 1.dp,
                                                color = if (isSelected || dragging) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant
                                                },
                                            ),
                                            RoundedCornerShape(8.dp),
                                        )
                                        // Dragging needs a held finger, which TalkBack and Switch Access
                                        // can't give - so pages also move one step at a time from its actions menu.
                                        .semantics {
                                            if (!selecting) {
                                                val moves = buildList {
                                                    if (index > 0) add(CustomAccessibilityAction("Move earlier") { movePage(index, -1) })
                                                    if (index < order.lastIndex) add(CustomAccessibilityAction("Move later") { movePage(index, 1) })
                                                }
                                                customActions = moves
                                            }
                                        }
                                        .clickable(role = if (selecting) Role.Checkbox else Role.Button) {
                                            if (selecting) {
                                                haptics.toggle(on = !isSelected)
                                                selected = if (isSelected) selection - page.id else selection + page.id
                                            } else {
                                                onOpenPage(index)
                                            }
                                        },
                                ) {
                                    PageThumbnail(page, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize())
                                    if (selecting) {
                                        SelectionCheck(
                                            selected = isSelected,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingDelete) {
        val count = selection.size
        val wholeDocument = count == pages.size
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            // Deleting every page deletes the document too - say so up front.
            title = {
                Text(
                    when {
                        wholeDocument -> "Delete this document?"
                        count == 1 -> "Delete this page?"
                        else -> "Delete $count pages?"
                    }
                )
            },
            text = {
                Text(
                    if (wholeDocument) {
                        "These are all of its pages, so the whole document will be deleted. This can't be undone."
                    } else {
                        "This can't be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(Haptic.CONFIRM)
                    onDelete(selection)
                    pendingDelete = false
                    endSelection()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** Cards are drawn at a typical page's proportions; each page fills its card cropped, like the home grid. */
private const val PAGE_ASPECT = 0.75f

private val PageIdsSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })
