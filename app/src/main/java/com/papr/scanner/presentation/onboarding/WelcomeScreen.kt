package com.papr.scanner.presentation.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private class Slide(val icon: ImageVector, val title: String, val body: String)

private val SLIDES = listOf(
    Slide(
        Icons.Outlined.DocumentScanner,
        "Scan any paper",
        "Point your camera at a page. Papr finds the edges, straightens it and cleans it up. Scan one page or a whole stack.",
    ),
    Slide(
        Icons.Outlined.Gesture,
        "Mark up, sign and organize",
        "Highlight, write notes and add your signature. Reorder, rotate or replace pages whenever you like.",
    ),
    Slide(
        Icons.Outlined.IosShare,
        "Share it your way",
        "Export a PDF or images and send them anywhere. Your documents stay on this phone. Papr never uploads them.",
    ),
)

/** The first-launch slides, also reachable from Settings > Show tour again. [onFinish] runs for Skip and Get started alike. */
@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == SLIDES.lastIndex

    // Back steps through the slides before it leaves them.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
                if (!isLast) {
                    TextButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterEnd)) { Text("Skip") }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { index ->
                SlideContent(SLIDES[index])
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                PageDots(count = SLIDES.size, current = pagerState.currentPage, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (isLast) onFinish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                ) {
                    Text(if (isLast) "Get started" else "Next")
                }
            }
        }
    }
}

@Composable
private fun SlideContent(slide: Slide) {
    // Scrolls on short or landscape screens and with large text, instead of clipping the words.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
    ) {
        SlideArt(slide.icon)
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            slide.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            slide.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

/** The icon on a tinted disc, with a tilted sheet behind it - the two sheets of the app icon. */
@Composable
private fun SlideArt(icon: ImageVector) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Box(
            modifier = Modifier
                .size(width = 124.dp, height = 156.dp)
                .rotate(-9f)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(136.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = "Page ${current + 1} of $count" },
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot width")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dot colour",
            )
            Box(modifier = Modifier.size(width = width, height = 8.dp).background(color, CircleShape))
        }
    }
}
