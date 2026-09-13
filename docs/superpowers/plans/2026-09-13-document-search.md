# Document Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend home-screen search to match OCR'd page content (ranked below title matches) and add an independent date-range filter, with a visual indicator when a result matched only via content.

**Architecture:** Extends the existing reactive-`Flow`-plus-in-memory-filter pattern (no FTS, no new dependency). A new `DocumentRepository.getAllPages()` exposes every page's `extractedText`; `HomeViewModel` combines it with documents/search/folder/date-range state, using a small pure `matchDocument` function to rank each document's match tier; the UI gets a date-range picker (Material3, already available) and a content-match badge.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `DateRangePicker`), Room, JUnit.

## Global Constraints

- No unrelated refactors — every changed line traces to this plan's search/date-filter work (per project CLAUDE.md).
- No new dependencies (Material3's `DateRangePicker`/`DatePickerDialog` are already available via this project's Compose BOM).
- No Room schema/migration changes — `getAllPages()` is a new query on the existing `pages` table, not a schema change.
- This project has no Robolectric — only plain JUnit tests of pure logic are possible. Pure logic extracted from a class that isn't otherwise unit-testable goes in a top-level `internal fun`/`enum` in the same file — the established pattern from `BitmapPdfCompiler.kt`'s `nextScale` and `InvisibleTextLayerInjector.kt`'s `helveticaWidth`/`horizontalScalePercent` (both from earlier sub-projects of this engagement) — match that style exactly.
- **Git hygiene:** check diff *content*, not just diff size, before committing any file that might carry pre-existing uncommitted work. `MainActivity.kt`, `DocumentDetailScreen.kt`, `DocumentDetailViewModel.kt`, `AndroidManifest.xml`, and `FileUtils.kt` have already had their pre-existing WIP cleanly separated in earlier sub-projects, so they're low-risk now. `BillingManager.kt`, `PaprNavHost.kt`, and `DocumentScannerManager.kt` still carry real uncommitted pre-existing work — none of this plan's tasks touch those three files, so this is just a heads-up, not something to act on.
- Read each file's current on-disk content before editing — confirm it matches what's shown in each step rather than assuming, in case something else touched it since this plan was written.

---

### Task 1: Data layer — expose all pages for content search

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/data/local/db/PageDao.kt`
- Modify: `app/src/main/java/com/papr/scanner/data/repository/DocumentRepository.kt`
- Modify: `app/src/main/java/com/papr/scanner/data/repository/DocumentRepositoryImpl.kt`

**Interfaces:**
- Produces: `DocumentRepository.getAllPages(): Flow<List<Page>>`, consumed by Task 2's `HomeViewModel`.

No TDD test-first step here: plain Room DAO/repository wiring, consistent with how the rest of this repository layer has never been unit tested (thin pass-through code) — verified via compile + the manual checklist in Task 3.

- [ ] **Step 1: Add the DAO query**

Read `app/src/main/java/com/papr/scanner/data/local/db/PageDao.kt` first to confirm current content. Add this method right after `getForDocument`:
```kotlin
    @Query("SELECT * FROM pages")
    fun getAll(): Flow<List<PageEntity>>
```

- [ ] **Step 2: Add it to the repository interface**

Read `app/src/main/java/com/papr/scanner/data/repository/DocumentRepository.kt` first to confirm current content. Change:
```kotlin
    fun getPagesForDocument(documentId: Long): Flow<List<Page>>
```
to:
```kotlin
    fun getPagesForDocument(documentId: Long): Flow<List<Page>>
    fun getAllPages(): Flow<List<Page>>
```

- [ ] **Step 3: Implement it**

Read `app/src/main/java/com/papr/scanner/data/repository/DocumentRepositoryImpl.kt` first to confirm current content. Add this override right after `getPagesForDocument`:
```kotlin
    override fun getAllPages(): Flow<List<Page>> =
        pageDao.getAll().map { entities -> entities.map { it.toDomain() } }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/papr/scanner/data/local/db/PageDao.kt app/src/main/java/com/papr/scanner/data/repository/DocumentRepository.kt app/src/main/java/com/papr/scanner/data/repository/DocumentRepositoryImpl.kt
git commit -m "Expose all pages via DocumentRepository, for content search"
```

---

### Task 2: Matching/ranking logic and HomeViewModel wiring

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt`
- Test: `app/src/test/java/com/papr/scanner/presentation/home/HomeViewModelMatchTest.kt`

**Interfaces:**
- Consumes: `DocumentRepository.getAllPages(): Flow<List<Page>>` (Task 1).
- Produces: `internal enum class SearchMatch { TITLE, CONTENT, NONE }` and `internal fun matchDocument(query: String, title: String, pageTexts: List<String?>): SearchMatch`, top-level in `HomeViewModel.kt`. `HomeUiState.selectedDateRange: LongRange?` and `HomeUiState.contentMatchedIds: Set<Long>`, consumed by Task 3's UI. `HomeViewModel.onDateRangeSelected(range: LongRange?)`, consumed by Task 3's UI.

- [ ] **Step 1: Write the failing tests for the matching logic**

Create `app/src/test/java/com/papr/scanner/presentation/home/HomeViewModelMatchTest.kt`:
```kotlin
package com.papr.scanner.presentation.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelMatchTest {

    @Test
    fun `matches by title when the query is found in the title`() {
        val result = matchDocument(query = "license", title = "Drivers License", pageTexts = emptyList())
        assertEquals(SearchMatch.TITLE, result)
    }

    @Test
    fun `matches by content when the query is only found in page text`() {
        val result = matchDocument(query = "license", title = "7sept", pageTexts = listOf("STATE DRIVERS LICENSE"))
        assertEquals(SearchMatch.CONTENT, result)
    }

    @Test
    fun `title match wins even when content also matches`() {
        val result = matchDocument(query = "license", title = "My License", pageTexts = listOf("license terms"))
        assertEquals(SearchMatch.TITLE, result)
    }

    @Test
    fun `does not match when neither title nor any page contains the query`() {
        val result = matchDocument(query = "passport", title = "Drivers License", pageTexts = listOf("STATE DRIVERS LICENSE"))
        assertEquals(SearchMatch.NONE, result)
    }

    @Test
    fun `is case-insensitive`() {
        val result = matchDocument(query = "LICENSE", title = "drivers license", pageTexts = emptyList())
        assertEquals(SearchMatch.TITLE, result)
    }

    @Test
    fun `ignores null page text without crashing`() {
        val result = matchDocument(query = "license", title = "7sept", pageTexts = listOf(null, "license"))
        assertEquals(SearchMatch.CONTENT, result)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.presentation.home.HomeViewModelMatchTest"`
Expected: FAIL to compile — `SearchMatch` and `matchDocument` are unresolved (they don't exist yet).

- [ ] **Step 3: Update `HomeUiState`**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeUiState.kt` first to confirm current content. Change:
```kotlin
data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val searchQuery: String = "",
    val folders: List<String> = emptyList(),
    val selectedFolder: String? = null,
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
)
```

- [ ] **Step 4: Rewrite `HomeViewModel`**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt` first to confirm current content. Replace the whole file with:
```kotlin
package com.papr.scanner.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.core.FileUtils
import com.papr.scanner.data.repository.DocumentRepository
import com.papr.scanner.domain.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    val uiState: StateFlow<HomeUiState> = combine(
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
        // filtered is already ordered most-recently-updated-first (DocumentDao.getAll()'s
        // own ORDER BY) - filter/mapNotNull preserve that order, and sortedBy is stable, so
        // title matches end up before content-only matches without losing recency order
        // within either group.
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

        HomeUiState(
            documents = visible,
            searchQuery = query,
            folders = folders,
            selectedFolder = folder,
            selectedDateRange = dateRange,
            contentMatchedIds = contentMatchedIds,
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

    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            documentRepository.deleteDocument(document) // Room cascade-deletes the page rows
            withContext(Dispatchers.IO) {
                FileUtils.documentDir(appContext, document.id).deleteRecursively()
                FileUtils.thumbnailFile(appContext, document.id).delete()
            }
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

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.papr.scanner.presentation.home.HomeViewModelMatchTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (this also exercises Task 1's `getAllPages()` being called from the `combine()` — Task 1 must be done first).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/home/HomeUiState.kt app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt app/src/test/java/com/papr/scanner/presentation/home/HomeViewModelMatchTest.kt
git commit -m "$(cat <<'EOF'
Rank search results by title vs. OCR-content match, add date-range state

Search previously only matched document titles. It now also matches
each document's pages' extractedText (populated since the OCR-rework
sub-project), with title matches ranked above content-only matches.
Also adds the date-range filter state HomeViewModel now tracks
alongside search and folder - the UI to drive it is the next task.
EOF
)"
```

---

### Task 3: UI — date range picker, active-range chip, content-match badge

I fetched the official Android developer documentation's Material3 date-range-picker-in-a-dialog example live (not from memory) to get this exactly right: there is no separate "DateRangePickerDialog" composable — the general `DatePickerDialog` is used as the dialog chrome, with `DateRangePicker` as its content, driven by `rememberDateRangePickerState()`.

**Files:**
- Create: `app/src/main/java/com/papr/scanner/presentation/home/components/DateRangeFilterButton.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `HomeUiState.selectedDateRange`/`contentMatchedIds`, `HomeViewModel.onDateRangeSelected` (Task 2).
- Produces: `DateRangeFilterButton(onDateRangeSelected: (LongRange?) -> Unit, modifier: Modifier = Modifier)` and `DateRangeChip(range: LongRange, onClear: () -> Unit, modifier: Modifier = Modifier)`, both consumed only by `HomeScreen.kt` in this same task.

No TDD test-first step: Compose UI with no meaningful pure logic beyond what Task 2 already tests, consistent with how this project's other UI-only changes were verified by compile + manual checklist only.

- [ ] **Step 1: Create the date-range picker button and active-range chip**

```kotlin
package com.papr.scanner.presentation.home.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeFilterButton(
    onDateRangeSelected: (LongRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }, modifier = modifier) {
        Icon(Icons.Default.DateRange, contentDescription = "Filter by date")
    }

    if (showDialog) {
        val dateRangePickerState = rememberDateRangePickerState()

        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = dateRangePickerState.selectedStartDateMillis
                    val end = dateRangePickerState.selectedEndDateMillis
                    onDateRangeSelected(if (start != null && end != null) start..end else null)
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                showModeToggle = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
fun DateRangeChip(range: LongRange, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val formatter = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    AssistChip(
        onClick = onClear,
        label = { Text("${formatter.format(Date(range.first))} - ${formatter.format(Date(range.last))}") },
        trailingIcon = {
            Icon(Icons.Default.Close, contentDescription = "Clear date filter", modifier = Modifier.size(18.dp))
        },
        modifier = modifier,
    )
}
```
Save as `app/src/main/java/com/papr/scanner/presentation/home/components/DateRangeFilterButton.kt`.

- [ ] **Step 2: Add the content-match badge to `DocumentGridItem`**

Read `app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt` first to confirm current content. Change the function signature:
```kotlin
fun DocumentGridItem(
    document: Document,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
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
) {
```
Add this new `Surface` right after the closing `}` of the existing page-count `Surface` block, still inside the same `Box`:
```kotlin
            if (isContentMatch) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "Matched in scanned text",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
```

- [ ] **Step 3: Wire it all into `HomeScreen`**

Read `app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt` first to confirm current content. Add these two imports alongside the existing `com.papr.scanner.presentation.home.components.*` imports:
```kotlin
import com.papr.scanner.presentation.home.components.DateRangeChip
import com.papr.scanner.presentation.home.components.DateRangeFilterButton
```

Change the `TopAppBar`'s `actions`:
```kotlin
                actions = {
                    IconButton(onClick = onUpgradeClick) {
                        Icon(Icons.Default.WorkspacePremium, contentDescription = "Go Pro")
                    }
                },
```
to:
```kotlin
                actions = {
                    DateRangeFilterButton(onDateRangeSelected = viewModel::onDateRangeSelected)
                    IconButton(onClick = onUpgradeClick) {
                        Icon(Icons.Default.WorkspacePremium, contentDescription = "Go Pro")
                    }
                },
```

Change:
```kotlin
            DocumentSearchBar(query = uiState.searchQuery, onQueryChange = viewModel::onSearchQueryChange)
            FilterChipsRow(
```
to:
```kotlin
            DocumentSearchBar(query = uiState.searchQuery, onQueryChange = viewModel::onSearchQueryChange)
            uiState.selectedDateRange?.let { range ->
                DateRangeChip(
                    range = range,
                    onClear = { viewModel.onDateRangeSelected(null) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            FilterChipsRow(
```

Change:
```kotlin
                        DocumentGridItem(
                            document = document,
                            onClick = { onDocumentClick(document.id) },
                            onLongClick = { pendingDelete = document },
                        )
```
to:
```kotlin
                        DocumentGridItem(
                            document = document,
                            onClick = { onDocumentClick(document.id) },
                            onLongClick = { pendingDelete = document },
                            isContentMatch = document.id in uiState.contentMatchedIds,
                        )
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/home/components/DateRangeFilterButton.kt app/src/main/java/com/papr/scanner/presentation/home/components/DocumentGridItem.kt app/src/main/java/com/papr/scanner/presentation/home/HomeScreen.kt
git commit -m "Add date-range filter and content-match badge to the home screen"
```

---

### Task 4: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full Gradle check**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL` for all four, 0 lint errors, all unit tests pass (including the 6 new `HomeViewModelMatchTest` cases).

- [ ] **Step 2: Manual on-device checklist**

No device/emulator is available in this environment — install and hand this checklist to the user:

```bash
./gradlew :app:installDebug
```
1. Search by a document's exact title — it shows up, no "Matched in scanned text" badge.
2. Search by a word that only appears in a scanned page's OCR'd text, not in any title — the document shows up, tagged with the "Matched in scanned text" badge.
3. Pick a query that matches one document's title and a different document's content only — confirm the title match sorts above the content match.
4. Tap the calendar icon in the top bar — the date-range picker dialog opens, lets you pick a start and end date, and confirming filters the grid to documents scanned within that range.
5. Confirm the active date range shows as a chip below the search bar, and tapping it clears the filter (grid returns to showing everything, subject to any other active filters).
6. Confirm the date filter combines sensibly with an active text search and/or the folder filter (e.g. narrows further, doesn't fight with them).

Report what you observe for each.

- [ ] **Step 3: Report findings**

Summarize what worked and what didn't. Anything unexpected is a new finding for a follow-up, not something to guess a fix for blind.
