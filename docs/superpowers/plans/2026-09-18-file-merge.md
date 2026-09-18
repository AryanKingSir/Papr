# File Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user select multiple documents on the home screen, reorder them on a confirm screen, and merge them into a new persisted document whose pages are the sources' pages concatenated in the chosen order — originals untouched.

**Architecture:** A new `DocumentMerger` (data layer) does the actual file-copy + DB-insert work, mirroring `MainActivity.handleNewDocument`'s existing "insert document → import pages → generate thumbnail → update document" shape. A new home-screen long-press multi-select mode (replacing today's single-item long-press-delete, which moves into the selection action bar) feeds a new `MergeConfirmScreen` + `MergeConfirmViewModel`, reached via a new nav route, that lets the user reorder/remove/name before confirming.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room, Navigation Compose, JUnit.

## Global Constraints

- No unrelated refactors — every changed line traces to this plan's merge feature (per project CLAUDE.md).
- No new dependencies.
- This project has no Robolectric — only plain JUnit tests of pure logic are possible. Pure logic extracted from a class that isn't otherwise unit-testable goes in a top-level `internal fun` in the same file — the established pattern from every prior sub-project of this engagement (e.g. `BitmapPdfCompiler.kt`'s `nextScale`, `HomeViewModel.kt`'s `matchDocument`) — match that style exactly.
- **Git hygiene:** check diff *content*, not just diff size, before committing any file that might carry pre-existing uncommitted work — checking size alone caused real mistakes in an earlier sub-project of this engagement. `PaprNavHost.kt` still has one real pre-existing uncommitted change as of the start of this sub-project (the `onAddPagesClick`/`onReplacePageClick` wiring plus a null-safety fix) — Task 3 below handles it explicitly with the same checkpoint-first pattern used for `AndroidManifest.xml` and `FileUtils.kt` in earlier sub-projects: commit that pre-existing diff on its own first, *then* make this plan's own edit.
- Material icon names used below (`Icons.Filled.MergeType`, plus the already-in-use `Close`/`Delete`) were verified to actually exist in this project's `material-icons-extended` dependency (unzipped and grepped the actual jar, not assumed from memory).
- Read each file's current on-disk content before editing — confirm it matches what's shown in each step rather than assuming, in case something else touched it since this plan was written.

---

### Task 1: `DocumentMerger` — the actual merge operation

**Files:**
- Create: `app/src/main/java/com/papr/scanner/data/repository/DocumentMerger.kt`
- Modify: `app/src/main/java/com/papr/scanner/di/AppContainer.kt`

**Interfaces:**
- Produces: `DocumentMerger(documentRepository: DocumentRepository, appContext: Context)` with `suspend fun merge(documentIds: List<Long>, title: String): Long` (returns the new document's ID). Consumed by Task 2's `MergeConfirmViewModel` via `AppContainer.documentMerger`.

No TDD test-first step: this is file-copy/DB orchestration with no logic that's meaningfully testable without Robolectric — same as `PageTextExtractor` (OCR-rework sub-project) had no test. Verified via compile + the manual checklist in Task 5.

- [ ] **Step 1: Create `DocumentMerger`**

```kotlin
package com.papr.scanner.data.repository

import android.content.Context
import com.papr.scanner.core.FileUtils
import com.papr.scanner.domain.model.Document
import com.papr.scanner.domain.model.Page
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Combines several existing documents' pages, in the given order, into one new persisted document. Originals are left untouched - pages are real file copies, not shared references. */
class DocumentMerger(
    private val documentRepository: DocumentRepository,
    private val appContext: Context,
) {
    suspend fun merge(documentIds: List<Long>, title: String): Long {
        val now = System.currentTimeMillis()
        val newDocumentId = documentRepository.insertDocument(
            Document(
                id = 0,
                title = title,
                createdAt = now,
                updatedAt = now,
                pageCount = 0,
                thumbnailUri = "",
                pdfUri = null,
                folder = null,
            )
        )

        val newDir = FileUtils.documentDir(appContext, newDocumentId)
        var nextPageIndex = 0
        val newPages = mutableListOf<Page>()
        for (documentId in documentIds) {
            val sourcePages = documentRepository.getPagesForDocument(documentId).first().sortedBy { it.pageIndex }
            for (page in sourcePages) {
                val destination = File(newDir, "page_${UUID.randomUUID()}.jpg")
                withContext(Dispatchers.IO) { File(page.imageUri).copyTo(destination) }
                newPages += Page(
                    id = 0,
                    documentId = newDocumentId,
                    pageIndex = nextPageIndex,
                    imageUri = destination.absolutePath,
                    extractedText = page.extractedText,
                )
                nextPageIndex++
            }
        }
        documentRepository.insertPages(newPages)

        val thumbnail = FileUtils.generateThumbnail(appContext, newDocumentId, File(newPages.first().imageUri))
        documentRepository.updateDocument(
            Document(
                id = newDocumentId,
                title = title,
                createdAt = now,
                updatedAt = now,
                pageCount = newPages.size,
                thumbnailUri = thumbnail.absolutePath,
                pdfUri = null,
                folder = null,
            )
        )

        return newDocumentId
    }
}
```
Save as `app/src/main/java/com/papr/scanner/data/repository/DocumentMerger.kt`.

- [ ] **Step 2: Wire it into `AppContainer`**

Read `app/src/main/java/com/papr/scanner/di/AppContainer.kt` first to confirm current content. Add this property (place it right after `documentRepository`):
```kotlin
    val documentMerger: DocumentMerger by lazy {
        DocumentMerger(documentRepository, context)
    }
```
No new import needed — `DocumentMerger` is in the same `com.papr.scanner.data.repository` package `AppContainer` already imports `DocumentRepository`/`DocumentRepositoryImpl` from.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/papr/scanner/data/repository/DocumentMerger.kt app/src/main/java/com/papr/scanner/di/AppContainer.kt
git commit -m "Add DocumentMerger, combining documents' pages into a new persisted document"
```

---

### Task 2: `MergeConfirmViewModel` — reorder/rename state, TDD-ordered

**Files:**
- Create: `app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmUiState.kt`
- Create: `app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmViewModel.kt`
- Test: `app/src/test/java/com/papr/scanner/presentation/merge/MergeConfirmViewModelTest.kt`

**Interfaces:**
- Consumes: `AppContainer.documentMerger`/`documentRepository` (Task 1, and the pre-existing `DocumentRepository`).
- Produces: `MergeConfirmUiState(documents: List<Document>, title: String, isMerging: Boolean)`; `MergeConfirmViewModel(documentIds: List<Long>, documentRepository: DocumentRepository, documentMerger: DocumentMerger)` with `uiState: StateFlow<MergeConfirmUiState>`, `mergeComplete: SharedFlow<Long>`, `onTitleChange(title: String)`, `moveDocument(document: Document, direction: Int)`, `removeDocument(document: Document)`, `merge()` — all consumed by Task 3's `MergeConfirmScreen`. Top-level `internal fun defaultMergeTitle(titles: List<String>): String` and `internal fun <T> reordered(items: List<T>, index: Int, direction: Int): List<T>`, used by this task's own tests and by the class above.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/papr/scanner/presentation/merge/MergeConfirmViewModelTest.kt`:
```kotlin
package com.papr.scanner.presentation.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeConfirmViewModelTest {

    @Test
    fun `joins titles with a plus sign`() {
        val result = defaultMergeTitle(listOf("Front", "Back"))
        assertEquals("Front + Back", result)
    }

    @Test
    fun `truncates a very long joined title`() {
        val longTitles = listOf("A".repeat(40), "B".repeat(40))
        val result = defaultMergeTitle(longTitles)
        assertEquals(61, result.length) // 60 chars + the trailing ellipsis character
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `swaps an item with its next neighbor`() {
        val result = reordered(listOf("a", "b", "c"), index = 0, direction = 1)
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `swaps an item with its previous neighbor`() {
        val result = reordered(listOf("a", "b", "c"), index = 2, direction = -1)
        assertEquals(listOf("a", "c", "b"), result)
    }

    @Test
    fun `returns the list unchanged when moving the first item earlier`() {
        val result = reordered(listOf("a", "b", "c"), index = 0, direction = -1)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `returns the list unchanged when moving the last item later`() {
        val result = reordered(listOf("a", "b", "c"), index = 2, direction = 1)
        assertEquals(listOf("a", "b", "c"), result)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.presentation.merge.MergeConfirmViewModelTest"`
Expected: FAIL to compile — `defaultMergeTitle` and `reordered` are unresolved (they don't exist yet).

- [ ] **Step 3: Create `MergeConfirmUiState`**

```kotlin
package com.papr.scanner.presentation.merge

import com.papr.scanner.domain.model.Document

data class MergeConfirmUiState(
    val documents: List<Document> = emptyList(),
    val title: String = "",
    val isMerging: Boolean = false,
)
```
Save as `app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmUiState.kt`.

- [ ] **Step 4: Create `MergeConfirmViewModel`**

```kotlin
package com.papr.scanner.presentation.merge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.data.repository.DocumentMerger
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MergeConfirmViewModel(
    documentIds: List<Long>,
    private val documentRepository: DocumentRepository,
    private val documentMerger: DocumentMerger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeConfirmUiState())
    val uiState: StateFlow<MergeConfirmUiState> = _uiState.asStateFlow()

    private val _mergeComplete = MutableSharedFlow<Long>()
    val mergeComplete: SharedFlow<Long> = _mergeComplete.asSharedFlow()

    init {
        viewModelScope.launch {
            val documents = documentIds.mapNotNull { id -> documentRepository.getDocumentById(id).first() }
            _uiState.value = MergeConfirmUiState(
                documents = documents,
                title = defaultMergeTitle(documents.map { it.title }),
            )
        }
    }

    fun onTitleChange(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun moveDocument(document: Document, direction: Int) {
        val documents = _uiState.value.documents
        val index = documents.indexOfFirst { it.id == document.id }
        if (index == -1) return
        _uiState.value = _uiState.value.copy(documents = reordered(documents, index, direction))
    }

    fun removeDocument(document: Document) {
        _uiState.value = _uiState.value.copy(documents = _uiState.value.documents.filter { it.id != document.id })
    }

    fun merge() {
        val state = _uiState.value
        if (state.documents.size < 2 || state.title.isBlank() || state.isMerging) return
        viewModelScope.launch {
            _uiState.value = state.copy(isMerging = true)
            val newDocumentId = documentMerger.merge(state.documents.map { it.id }, state.title)
            _mergeComplete.emit(newDocumentId)
        }
    }
}

private const val MAX_DEFAULT_TITLE_LENGTH = 60

/** Builds a default merged-document title by joining the source titles, truncated with an ellipsis if the join is too long. */
internal fun defaultMergeTitle(titles: List<String>): String {
    val joined = titles.joinToString(" + ")
    return if (joined.length > MAX_DEFAULT_TITLE_LENGTH) joined.take(MAX_DEFAULT_TITLE_LENGTH).trimEnd() + "…" else joined
}

/** Swaps the item at [index] with its neighbor in [direction] (-1 earlier, +1 later); returns [items] unchanged if that neighbor is out of bounds. */
internal fun <T> reordered(items: List<T>, index: Int, direction: Int): List<T> {
    val targetIndex = index + direction
    if (index !in items.indices || targetIndex !in items.indices) return items
    return items.toMutableList().apply {
        val temp = this[index]
        this[index] = this[targetIndex]
        this[targetIndex] = temp
    }
}
```
Save as `app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmViewModel.kt`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.presentation.merge.MergeConfirmViewModelTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmUiState.kt app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmViewModel.kt app/src/test/java/com/papr/scanner/presentation/merge/MergeConfirmViewModelTest.kt
git commit -m "Add MergeConfirmViewModel: load selected documents, reorder/rename/remove, run the merge"
```

---

### Task 3: `MergeConfirmScreen` and its nav route

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/presentation/navigation/Screen.kt`
- Create: `app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmScreen.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt`

**Interfaces:**
- Consumes: `MergeConfirmViewModel`/`MergeConfirmUiState` (Task 2).
- Produces: `Screen.MergeConfirm` route (`createRoute(documentIds: List<Long>): String`), and `MergeConfirmScreen(documentIds: List<Long>, onBack: () -> Unit, onMergeComplete: (documentId: Long) -> Unit)`. The route exists and is fully self-contained after this task — nothing navigates to it yet from the home screen; that's Task 4.

No TDD test-first step: Compose UI and a small nav-route addition, no new pure logic beyond what Task 2 already tests.

- [ ] **Step 1: Add the `MergeConfirm` route**

Read `app/src/main/java/com/papr/scanner/presentation/navigation/Screen.kt` first to confirm current content. Change:
```kotlin
package com.papr.scanner.presentation.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")

    data object DocumentDetail : Screen("document/{documentId}") {
        const val ARG_DOCUMENT_ID = "documentId"
        fun createRoute(documentId: Long) = "document/$documentId"
    }
}
```
to:
```kotlin
package com.papr.scanner.presentation.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")

    data object DocumentDetail : Screen("document/{documentId}") {
        const val ARG_DOCUMENT_ID = "documentId"
        fun createRoute(documentId: Long) = "document/$documentId"
    }

    data object MergeConfirm : Screen("merge/{documentIds}") {
        const val ARG_DOCUMENT_IDS = "documentIds"
        fun createRoute(documentIds: List<Long>) = "merge/${documentIds.joinToString(",")}"
    }
}
```

- [ ] **Step 2: Create `MergeConfirmScreen`**

```kotlin
package com.papr.scanner.presentation.merge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.Document
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.paprViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeConfirmScreen(
    documentIds: List<Long>,
    onBack: () -> Unit,
    onMergeComplete: (documentId: Long) -> Unit,
) {
    val viewModel = paprViewModel { container ->
        MergeConfirmViewModel(documentIds, container.documentRepository, container.documentMerger)
    }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.mergeComplete.collect { newDocumentId -> onMergeComplete(newDocumentId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge documents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(uiState.documents, key = { _, document -> document.id }) { index, document ->
                    MergeDocumentRow(
                        document = document,
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.documents.size - 1,
                        onMoveUp = { viewModel.moveDocument(document, -1) },
                        onMoveDown = { viewModel.moveDocument(document, 1) },
                        onRemove = { viewModel.removeDocument(document) },
                    )
                }
            }
            Button(
                onClick = viewModel::merge,
                enabled = uiState.documents.size >= 2 && uiState.title.isNotBlank() && !uiState.isMerging,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (uiState.isMerging) "Merging…" else "Merge")
            }
        }
    }
}

@Composable
private fun MergeDocumentRow(
    document: Document,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageImage(
            file = document.thumbnailUri.takeIf { it.isNotBlank() }?.let { File(it) },
            contentDescription = document.title,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = document.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move earlier")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move later")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove from merge")
        }
    }
}
```
Save as `app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmScreen.kt`.

- [ ] **Step 3: Handle `PaprNavHost.kt`'s pre-existing uncommitted content**

Run `git diff -- app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt` and confirm it shows the pre-existing `onAddPagesClick`/`onReplacePageClick` wiring plus a null-safety fix to the `DocumentDetail` route's arg handling (added in an earlier sub-project of this engagement, never committed). If it shows something different, stop and re-read this plan's Global Constraints section before proceeding.

If it matches, commit that pre-existing diff on its own first:
```bash
git add app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt
git commit -m "Checkpoint pre-existing add-pages/replace-page nav wiring"
```

- [ ] **Step 4: Add the `MergeConfirm` destination**

Read `app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt` now (it should be clean — no uncommitted changes, since Step 3 just committed the only pre-existing one). Add the import:
```kotlin
import com.papr.scanner.presentation.merge.MergeConfirmScreen
```
Add this new destination inside the `NavHost` block, right after the `DocumentDetail` composable's closing `}`:
```kotlin
        composable(
            route = Screen.MergeConfirm.route,
            arguments = listOf(navArgument(Screen.MergeConfirm.ARG_DOCUMENT_IDS) { type = NavType.StringType }),
        ) { backStackEntry ->
            val idsArg = backStackEntry.arguments?.getString(Screen.MergeConfirm.ARG_DOCUMENT_IDS)
            val documentIds = idsArg?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
            if (documentIds.size < 2) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                MergeConfirmScreen(
                    documentIds = documentIds,
                    onBack = { navController.popBackStack() },
                    onMergeComplete = { newDocumentId ->
                        navController.navigate(Screen.DocumentDetail.createRoute(newDocumentId)) {
                            popUpTo(Screen.MergeConfirm.route) { inclusive = true }
                        }
                    },
                )
            }
        }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Verify this task's own diff is scoped correctly, then commit**

```bash
git diff -- app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt
```
Expected: only the new `MergeConfirm` composable block and its import — Step 3 already isolated the pre-existing content.
```bash
git add app/src/main/java/com/papr/scanner/presentation/navigation/Screen.kt app/src/main/java/com/papr/scanner/presentation/merge/MergeConfirmScreen.kt app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt
git commit -m "Add the merge-confirm screen and its nav route"
```

---

### Task 4: Home screen selection mode

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt`

**Interfaces:**
- Consumes: `Screen.MergeConfirm.createRoute(List<Long>)` (Task 3).
- Produces: `HomeScreen`'s new `onMergeClick: (documentIds: List<Long>) -> Unit` parameter, wired by `PaprNavHost`.

No TDD test-first step: Compose UI plus ViewModel state wiring, no new pure logic beyond what Task 2's tests already cover.

- [ ] **Step 1: Add selection-mode state to `HomeUiState`**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeUiState.kt` first to confirm current content. Change:
```kotlin
data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val searchQuery: String = "",
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val selectedDateRange: LongRange? = null,
    val contentMatchedIds: Set<Long> = emptySet(),
)
```
to:
```kotlin
data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val searchQuery: String = "",
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val selectedDateRange: LongRange? = null,
    val contentMatchedIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val selectedDocumentIds: List<Long> = emptyList(),
)
```

- [ ] **Step 2: Rewrite `HomeViewModel`**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt` first to confirm current content (it should have `SearchMatch`/`matchDocument` from the document-search sub-project — that's expected). Replace the whole file with:
```kotlin
package com.papr.scanner.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val documentRepository: DocumentRepository,
    private val appContext: Context,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow<String?>(null)
    private val selectedDateRange = MutableStateFlow<LongRange?>(null)
    private val isSelectionMode = MutableStateFlow(false)
    private val selectedDocumentIds = MutableStateFlow<List<Long>>(emptyList())

    private data class SearchResults(
        val documents: List<Document>,
        val searchQuery: String,
        val folders: List<String>,
        val selectedFolder: String?,
        val selectedDateRange: LongRange?,
        val contentMatchedIds: Set<Long>,
    )

    // Kept as its own 5-flow combine (the direct combine() overloads only go up to 5
    // arguments) and merged with the two selection-state flows below in a second stage,
    // rather than trying to combine all 7 sources in one call.
    private val searchResults: Flow<SearchResults> = combine(
        documentRepository.getAllDocuments(),
        documentRepository.getAllPages(),
        searchQuery,
        selectedFolder,
        selectedDateRange,
    ) { documents, pages, query, folder, dateRange ->
        val folders = documents.mapNotNull { it.folder }.distinct().sorted()
        val pagesByDocument = pages.groupBy { it.documentId }

        val filtered = documents
            .filter { folder == null || it.folder == folder }
            .filter { dateRange == null || it.createdAt in dateRange }

        val contentMatchedIds = mutableSetOf<Long>()
        val visible = if (query.isBlank()) {
            filtered
        } else {
            filtered.mapNotNull { document ->
                val pageTexts = pagesByDocument[document.id].orEmpty().map { it.extractedText }
                when (matchDocument(query, document.title, pageTexts)) {
                    SearchMatch.TITLE -> document
                    SearchMatch.CONTENT -> { contentMatchedIds += document.id; document }
                    SearchMatch.NONE -> null
                }
            }.sortedBy { document -> if (document.id in contentMatchedIds) 1 else 0 }
        }

        SearchResults(
            documents = visible,
            searchQuery = query,
            folders = folders,
            selectedFolder = folder,
            selectedDateRange = dateRange,
            contentMatchedIds = contentMatchedIds,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        searchResults,
        isSelectionMode,
        selectedDocumentIds,
    ) { results, selectionMode, selectedIds ->
        HomeUiState(
            documents = results.documents,
            searchQuery = results.searchQuery,
            folders = results.folders,
            selectedFolder = results.selectedFolder,
            selectedDateRange = results.selectedDateRange,
            contentMatchedIds = results.contentMatchedIds,
            isSelectionMode = selectionMode,
            selectedDocumentIds = selectedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onFolderSelected(folder: String?) {
        selectedFolder.value = folder
    }

    fun onDateRangeSelected(range: LongRange?) {
        selectedDateRange.value = range
    }

    fun onDocumentLongPress(document: Document) {
        isSelectionMode.value = true
        if (document.id !in selectedDocumentIds.value) {
            selectedDocumentIds.value = selectedDocumentIds.value + document.id
        }
    }

    fun toggleSelection(documentId: Long) {
        val current = selectedDocumentIds.value
        selectedDocumentIds.value = if (documentId in current) current - documentId else current + documentId
    }

    fun clearSelection() {
        isSelectionMode.value = false
        selectedDocumentIds.value = emptyList()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            // Looked up by ID directly rather than filtered from the current uiState -
            // a document can stay "selected" while an active search/folder/date filter
            // hides it from view, and it still needs to be deleted correctly.
            selectedDocumentIds.value.forEach { id ->
                documentRepository.getDocumentById(id).first()?.let { deleteDocumentFiles(it) }
            }
            clearSelection()
        }
    }

    private suspend fun deleteDocumentFiles(document: Document) {
        documentRepository.deleteDocument(document) // Room cascade-deletes the page rows
        withContext(Dispatchers.IO) {
            FileUtils.documentDir(appContext, document.id).deleteRecursively()
            FileUtils.thumbnailFile(appContext, document.id).delete()
        }
    }
}

internal enum class SearchMatch { TITLE, CONTENT, NONE }

/** How (if at all) [query] matches a document, given its [title] and its pages' recognized text - a title match outranks a content-only match. */
internal fun matchDocument(query: String, title: String, pageTexts: List<String?>): SearchMatch {
    return when {
        title.contains(query, ignoreCase = true) -> SearchMatch.TITLE
        pageTexts.any { it != null && it.contains(query, ignoreCase = true) } -> SearchMatch.CONTENT
        else -> SearchMatch.NONE
    }
}
```

- [ ] **Step 3: Add the selection checkbox to `DocumentGridItem`**

Read `app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt` first to confirm current content (it should have `isContentMatch` from the document-search sub-project — expected). Add these imports:
```kotlin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
```
Change the function signature:
```kotlin
fun DocumentGridItem(
    document: Document,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isContentMatch: Boolean = false,
) {
```
to:
```kotlin
fun DocumentGridItem(
    document: Document,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isContentMatch: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
) {
```
Add this right after the `if (isContentMatch) { ... }` block, still inside the same `Box`:
```kotlin
            if (selectionMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(2.dp))
                }
            }
```

- [ ] **Step 4: Rewrite `HomeScreen`**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt` first to confirm current content (it should have the date-range filter from the document-search sub-project — expected). Replace the whole file with:
```kotlin
package com.papr.scanner.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.papr.scanner.R
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.home.components.DateRangeChip
import com.papr.scanner.presentation.home.components.DateRangeFilterButton
import com.papr.scanner.presentation.home.components.DocumentGridItem
import com.papr.scanner.presentation.home.components.DocumentSearchBar
import com.papr.scanner.presentation.home.components.EmptyState
import com.papr.scanner.presentation.home.components.FilterChipsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDocumentClick: (Long) -> Unit,
    onScanClick: () -> Unit,
    onUpgradeClick: () -> Unit,
    onMergeClick: (documentIds: List<Long>) -> Unit,
) {
    val viewModel = paprViewModel { container -> HomeViewModel(container.documentRepository, container.appContext) }
    val uiState by viewModel.uiState.collectAsState()
    var pendingDeleteSelection by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedDocumentIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { pendingDeleteSelection = true },
                            enabled = uiState.selectedDocumentIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                        IconButton(
                            onClick = { onMergeClick(uiState.selectedDocumentIds) },
                            enabled = uiState.selectedDocumentIds.size >= 2,
                        ) {
                            Icon(Icons.Default.MergeType, contentDescription = "Merge selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_paper),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Papr")
                        }
                    },
                    actions = {
                        DateRangeFilterButton(onDateRangeSelected = viewModel::onDateRangeSelected)
                        IconButton(onClick = onUpgradeClick) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = "Go Pro")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onScanClick) {
                Icon(Icons.Default.DocumentScanner, contentDescription = "Scan a document")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            DocumentSearchBar(query = uiState.searchQuery, onQueryChange = viewModel::onSearchQueryChange)
            uiState.selectedDateRange?.let { range ->
                DateRangeChip(
                    range = range,
                    onClear = { viewModel.onDateRangeSelected(null) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            FilterChipsRow(
                folders = uiState.folders,
                selectedFolder = uiState.selectedFolder,
                onFolderSelected = viewModel::onFolderSelected,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (uiState.documents.isEmpty()) {
                EmptyState(onScanClick = onScanClick)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(uiState.documents, key = { it.id }) { document ->
                        DocumentGridItem(
                            document = document,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(document.id)
                                } else {
                                    onDocumentClick(document.id)
                                }
                            },
                            onLongClick = { viewModel.onDocumentLongPress(document) },
                            isContentMatch = document.id in uiState.contentMatchedIds,
                            selectionMode = uiState.isSelectionMode,
                            isSelected = document.id in uiState.selectedDocumentIds,
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteSelection) {
        val count = uiState.selectedDocumentIds.size
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = false },
            title = { Text(if (count == 1) "Delete this document?" else "Delete $count documents?") },
            text = { Text("This removes all their pages. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    pendingDeleteSelection = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelection = false }) { Text("Cancel") }
            },
        )
    }
}
```

- [ ] **Step 5: Wire `onMergeClick` in `PaprNavHost`**

Read `app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt` first to confirm current content (it should already have the `MergeConfirm` destination from Task 3). Change:
```kotlin
            HomeScreen(
                onDocumentClick = { id -> navController.navigate(Screen.DocumentDetail.createRoute(id)) },
                onScanClick = onScanClick,
                onUpgradeClick = { showPaywall = true },
            )
```
to:
```kotlin
            HomeScreen(
                onDocumentClick = { id -> navController.navigate(Screen.DocumentDetail.createRoute(id)) },
                onScanClick = onScanClick,
                onUpgradeClick = { showPaywall = true },
                onMergeClick = { ids -> navController.navigate(Screen.MergeConfirm.createRoute(ids)) },
            )
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (this task adds no new tests itself, but must not break the existing `HomeViewModelMatchTest`/`MergeConfirmViewModelTest` suites).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/home/HomeUiState.kt app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt app/src/main/java/com/papr/scanner/presentation/navigation/PaprNavHost.kt
git commit -m "$(cat <<'EOF'
Add home-screen multi-select mode, wired to merge and bulk delete

Long-press now enters a selection mode (checkboxes on cards) instead
of immediately confirming a single delete - delete moves into the
selection action bar, now supporting multiple documents at once, and
a new Merge action (enabled at 2+ selected) navigates to the
merge-confirm screen with the documents in tap order.
EOF
)"
```

---

### Task 5: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full Gradle check**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL` for all four, 0 lint errors, all unit tests pass (including the 6 new `MergeConfirmViewModelTest` cases).

- [ ] **Step 2: Manual on-device checklist**

No device/emulator is available in this environment — install and hand this checklist to the user:

```bash
./gradlew :app:installDebug
```
1. Long-press a document — selection mode starts, that document is checked, the top bar shows "1 selected" with Close/Delete/Merge actions (Merge disabled at 1 selected).
2. Tap other documents — they toggle selection, the count updates, Merge becomes enabled at 2+.
3. Tap the close (✕) action — selection mode exits, grid returns to normal.
4. Select 1 document, tap Delete — confirmation dialog, singular wording ("Delete this document?"), confirming deletes it.
5. Select 2+ documents, tap Delete — confirmation dialog, plural wording ("Delete N documents?"), confirming deletes all of them and the originals are gone.
6. Select 2+ documents (note the order you tap them in), tap Merge — the confirm screen opens listing them in that same order, each with a thumbnail.
7. On the confirm screen: move an item up/down (order changes correctly, buttons disable at the ends), remove an item (list shrinks, Merge button disables if fewer than 2 remain), edit the pre-filled title.
8. Tap Merge — a new document opens (not the confirm screen), containing all the source documents' pages concatenated in the order shown on the confirm screen, with a correct page count and thumbnail.
9. Go back from the newly merged document — you land on Home, not the confirm screen.
10. Check the original source documents are still present, unchanged, in the home grid.
11. In the merged document, rotate or delete a page, then open one of the original source documents and confirm it's unaffected (proves pages were really copied, not shared).

Report what you observe for each.

- [ ] **Step 3: Report findings**

Summarize what worked and what didn't. Anything unexpected is a new finding for a follow-up, not something to guess a fix for blind.
