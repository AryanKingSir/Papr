# Stability & Crash Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the Papr Android app to a compiling, buildable state by finishing the in-flight "edit a saved document" UI wiring, then fix the concrete main-thread-blocking and state-loss bugs found during a static stability audit — verified by a clean build and a full manual smoke test.

**Architecture:** No new architecture. This plan only touches existing files (`DocumentDetailScreen.kt`, `HomeViewModel.kt`, `DocumentDetailViewModel.kt`, `MainActivity.kt`) with surgical, in-place fixes. No new classes, no new dependencies, no new DI wiring.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Android ViewModel + `viewModelScope`, Kotlin Coroutines, Room (via `DocumentRepository`), Navigation Compose.

## Global Constraints

- No unrelated refactors — every changed line must trace to one of this plan's findings (per project CLAUDE.md).
- No new dependencies, no new crash/ANR logging/reporting infrastructure (user explicitly declined this for the current pass — see spec Risks section).
- Match existing patterns: blocking file I/O goes through `withContext(Dispatchers.IO) { ... }`, exactly like `FileUtils.rotateJpeg`/`FileUtils.generateThumbnail` already do.
- No changes to `MainActivity`'s `ScanTarget` handling logic, `DocumentRepository`, or `DocumentDetailViewModel`'s existing page-mutation flows beyond what each task specifies — that plumbing already works.
- This project has no Robolectric/instrumented test infra (only plain JUnit unit tests exist, testing pure functions — see `FileUtilsTest.kt`). Do not add test infrastructure as a side effect of these fixes; verify Android-lifecycle-dependent fixes (Task 3, and the delete-flow fixes in Task 2) manually, as described in each task.

---

### Task 1: Finish document-editing UI wiring (restores compilation)

`PaprNavHost.kt` already passes `onAddPagesClick`/`onReplacePageClick` callbacks down to `DocumentDetailScreen`, and `MainActivity.kt` already has full working handlers (`handleAppendPages`, `handleReplacePage`) behind those callbacks. `DocumentDetailScreen` just never got the matching parameters or UI, which is the entire current compile error:
```
PaprNavHost.kt:54:21 No parameter with name 'onAddPagesClick' found.
PaprNavHost.kt:55:21 No parameter with name 'onReplacePageClick' found.
```

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailScreen.kt`

**Interfaces:**
- Consumes: `PaprNavHost` already calls
  `DocumentDetailScreen(documentId, onBack, onAddPagesClick = { onAddPagesClick(documentId) }, onReplacePageClick = { pageId, pageIndex -> onReplacePageClick(documentId, pageId, pageIndex) })`
  — i.e. this task's new parameters must be named and typed exactly
  `onAddPagesClick: () -> Unit` and `onReplacePageClick: (pageId: Long, pageIndex: Int) -> Unit`.
- Produces: nothing new consumed by later tasks in this plan.

- [ ] **Step 1: Add the two new imports needed for the new icons**

In `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailScreen.kt`, add these two imports alongside the existing `androidx.compose.material.icons.filled.*` imports (after the `import androidx.compose.material.icons.filled.Draw` line):

```kotlin
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.NoteAdd
```

- [ ] **Step 2: Add the two new parameters to `DocumentDetailScreen`**

Change:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(documentId: Long, onBack: () -> Unit) {
```
to:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    documentId: Long,
    onBack: () -> Unit,
    onAddPagesClick: () -> Unit,
    onReplacePageClick: (pageId: Long, pageIndex: Int) -> Unit,
) {
```

- [ ] **Step 3: Add an "Add pages" action to the top app bar**

Change the `actions` block of the `TopAppBar`:
```kotlin
                actions = {
                    if (isEditingTitle) {
                        TextButton(onClick = {
                            viewModel.updateTitle(titleDraft)
                            isEditingTitle = false
                        }) { Text("Save") }
                    } else {
                        IconButton(onClick = { showExportOptions = true }, enabled = uiState.pages.isNotEmpty()) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export")
                        }
                    }
                },
```
to:
```kotlin
                actions = {
                    if (isEditingTitle) {
                        TextButton(onClick = {
                            viewModel.updateTitle(titleDraft)
                            isEditingTitle = false
                        }) { Text("Save") }
                    } else {
                        IconButton(onClick = onAddPagesClick) {
                            Icon(Icons.Filled.NoteAdd, contentDescription = "Add pages")
                        }
                        IconButton(onClick = { showExportOptions = true }, enabled = uiState.pages.isNotEmpty()) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export")
                        }
                    }
                },
```

- [ ] **Step 4: Add a "Replace page" action to the per-page action row**

Change the bottom action `Row` (currently move-earlier / rotate / sign / delete / move-later):
```kotlin
                IconButton(onClick = { showSignatureEditor = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Draw, contentDescription = "Sign page")
                }
                IconButton(onClick = { pendingDeletePage = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                }
```
to:
```kotlin
                IconButton(onClick = { showSignatureEditor = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Draw, contentDescription = "Sign page")
                }
                IconButton(
                    onClick = { currentPage?.let { onReplacePageClick(it.id, it.pageIndex) } },
                    enabled = currentPage != null,
                ) {
                    Icon(Icons.Filled.FindReplace, contentDescription = "Replace page")
                }
                IconButton(onClick = { pendingDeletePage = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                }
```

- [ ] **Step 5: Compile and confirm the build error is gone**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (no more `onAddPagesClick`/`onReplacePageClick` errors in `PaprNavHost.kt`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailScreen.kt
git commit -m "Wire up add-pages/replace-page UI in DocumentDetailScreen, restoring compilation"
```

---

### Task 2: Move blocking file deletes off the main thread

Found via static audit: `viewModelScope.launch { ... }` (and `MainActivity`'s `lifecycleScope.launch { ... }`) default to `Dispatchers.Main`. Three call sites do raw, blocking `java.io.File` deletes directly inside such a block with no `withContext(Dispatchers.IO)`, unlike `FileUtils.rotateJpeg`/`generateThumbnail` which already dispatch correctly. `deleteRecursively()` on a multi-page document's directory (several full-resolution JPEGs) blocking the main thread is a plausible concrete cause of the reported random stutters/freezes.

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt:147-179` (`deletePage`) and `:191-198` (`deleteDocumentLocked`)
- Modify: `app/src/main/java/com/papr/scanner/MainActivity.kt:172-186` (`handleReplacePage`)

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new consumed by later tasks.

- [ ] **Step 1: Fix `HomeViewModel.deleteDocument`**

Add imports to `app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt` (alongside the existing `kotlinx.coroutines.flow.*` imports):
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Change:
```kotlin
    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            documentRepository.deleteDocument(document) // Room cascade-deletes the page rows
            FileUtils.documentDir(appContext, document.id).deleteRecursively()
            FileUtils.thumbnailFile(appContext, document.id).delete()
        }
    }
```
to:
```kotlin
    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            documentRepository.deleteDocument(document) // Room cascade-deletes the page rows
            withContext(Dispatchers.IO) {
                FileUtils.documentDir(appContext, document.id).deleteRecursively()
                FileUtils.thumbnailFile(appContext, document.id).delete()
            }
        }
    }
```

- [ ] **Step 2: Fix `DocumentDetailViewModel.deletePage` and `deleteDocumentLocked`**

`Dispatchers` and `withContext` are already imported in `DocumentDetailViewModel.kt` (used by `buildSignaturePlacement`) — no new imports needed.

Change (`deletePage`, around line 150-152):
```kotlin
                fileMutex.withLock {
                    documentRepository.deletePage(page)
                    File(page.imageUri).delete()
```
to:
```kotlin
                fileMutex.withLock {
                    documentRepository.deletePage(page)
                    withContext(Dispatchers.IO) { File(page.imageUri).delete() }
```

Change (`deleteDocumentLocked`, around line 192-197):
```kotlin
    private suspend fun deleteDocumentLocked() {
        val document = uiState.value.document ?: return
        documentRepository.deleteDocument(document)
        FileUtils.documentDir(appContext, document.id).deleteRecursively()
        FileUtils.thumbnailFile(appContext, document.id).delete()
        isDeleted.value = true
    }
```
to:
```kotlin
    private suspend fun deleteDocumentLocked() {
        val document = uiState.value.document ?: return
        documentRepository.deleteDocument(document)
        withContext(Dispatchers.IO) {
            FileUtils.documentDir(appContext, document.id).deleteRecursively()
            FileUtils.thumbnailFile(appContext, document.id).delete()
        }
        isDeleted.value = true
    }
```

- [ ] **Step 3: Fix `MainActivity.handleReplacePage`**

Add imports to `app/src/main/java/com/papr/scanner/MainActivity.kt` (alongside the existing `kotlinx.coroutines.*` imports):
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Change:
```kotlin
    private suspend fun handleReplacePage(container: AppContainer, target: ScanTarget.ReplacePage, pageUris: List<Uri>) {
        val document = container.documentRepository.getDocumentById(target.documentId).first() ?: return
        val existingPages = container.documentRepository.getPagesForDocument(target.documentId).first()
        val page = existingPages.firstOrNull { it.id == target.pageId } ?: return

        val newFile = container.documentScannerManager.importScannedPages(target.documentId, pageUris.take(1)).first()
        File(page.imageUri).delete()
        container.documentRepository.updatePage(page.copy(imageUri = newFile.absolutePath))
```
to:
```kotlin
    private suspend fun handleReplacePage(container: AppContainer, target: ScanTarget.ReplacePage, pageUris: List<Uri>) {
        val document = container.documentRepository.getDocumentById(target.documentId).first() ?: return
        val existingPages = container.documentRepository.getPagesForDocument(target.documentId).first()
        val page = existingPages.firstOrNull { it.id == target.pageId } ?: return

        val newFile = container.documentScannerManager.importScannedPages(target.documentId, pageUris.take(1)).first()
        withContext(Dispatchers.IO) { File(page.imageUri).delete() }
        container.documentRepository.updatePage(page.copy(imageUri = newFile.absolutePath))
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification (no test infra exists for Android-dispatcher behavior — see Global Constraints)**

Install the debug build (`./gradlew :app:installDebug`), then on-device:
1. Scan a document with several pages (5+), open it, tap the new "Replace page" icon on one page, confirm it re-scans and replaces that page without any visible UI hang.
2. Delete a single page from a multi-page document (existing delete-page flow) — confirm no visible hang.
3. From the home screen, delete a whole multi-page document — confirm the app stays responsive (no frozen frame) while it deletes.
Report what you observe.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/papr/scanner/presentation/home/HomeViewModel.kt app/src/main/java/com/papr/scanner/presentation/detail/DocumentDetailViewModel.kt app/src/main/java/com/papr/scanner/MainActivity.kt
git commit -m "Move blocking file deletes off the main thread in delete/replace flows"
```

---

### Task 3: Survive process death / recreation during an in-flight scan

Found via static audit: `MainActivity.pendingScanTarget` (which page/document a scan result should be applied to) is a plain `var`, not persisted across Activity recreation. While the external ML Kit scanner Activity is on top, Android can kill `MainActivity`'s process under memory pressure (this is normal background-process behavior, not a hypothetical edge case, and directly relevant to "add pages"/"replace page" which are exactly the flows that keep the target activity backgrounded during a scan). On recreation, `pendingScanTarget` silently resets to `ScanTarget.NewDocument`, so a scan meant to append pages or replace one page instead creates a spurious new document when the result comes back.

**Files:**
- Modify: `app/src/main/java/com/papr/scanner/MainActivity.kt`

**Interfaces:**
- Consumes: the `ScanTarget` sealed interface already defined in this file (`NewDocument`, `AppendPages(documentId: Long)`, `ReplacePage(documentId: Long, pageId: Long, pageIndex: Int)`).
- Produces: nothing consumed by other tasks.

- [ ] **Step 1: Persist `pendingScanTarget` into the Activity's saved-instance-state Bundle**

In `app/src/main/java/com/papr/scanner/MainActivity.kt`, change `onCreate` to restore from the bundle:
```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingScanTarget = restoreScanTarget(savedInstanceState)
        enableEdgeToEdge()
```
(the rest of `onCreate` is unchanged).

Add `onSaveInstanceState` and `restoreScanTarget` as new private members of `MainActivity` (place them after `startScan`, before `handleScanResult`):
```kotlin
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        when (val target = pendingScanTarget) {
            is ScanTarget.NewDocument -> outState.putString(KEY_SCAN_TARGET_TYPE, TYPE_NEW_DOCUMENT)
            is ScanTarget.AppendPages -> {
                outState.putString(KEY_SCAN_TARGET_TYPE, TYPE_APPEND_PAGES)
                outState.putLong(KEY_DOCUMENT_ID, target.documentId)
            }
            is ScanTarget.ReplacePage -> {
                outState.putString(KEY_SCAN_TARGET_TYPE, TYPE_REPLACE_PAGE)
                outState.putLong(KEY_DOCUMENT_ID, target.documentId)
                outState.putLong(KEY_PAGE_ID, target.pageId)
                outState.putInt(KEY_PAGE_INDEX, target.pageIndex)
            }
        }
    }

    private fun restoreScanTarget(savedInstanceState: Bundle?): ScanTarget {
        val bundle = savedInstanceState ?: return ScanTarget.NewDocument
        return when (bundle.getString(KEY_SCAN_TARGET_TYPE)) {
            TYPE_APPEND_PAGES -> ScanTarget.AppendPages(documentId = bundle.getLong(KEY_DOCUMENT_ID))
            TYPE_REPLACE_PAGE -> ScanTarget.ReplacePage(
                documentId = bundle.getLong(KEY_DOCUMENT_ID),
                pageId = bundle.getLong(KEY_PAGE_ID),
                pageIndex = bundle.getInt(KEY_PAGE_INDEX),
            )
            else -> ScanTarget.NewDocument
        }
    }
```

Add the new Bundle keys/type constants as a companion object at the bottom of the class (after `defaultTitle`):
```kotlin
    private companion object {
        const val KEY_SCAN_TARGET_TYPE = "pending_scan_target_type"
        const val KEY_DOCUMENT_ID = "pending_scan_document_id"
        const val KEY_PAGE_ID = "pending_scan_page_id"
        const val KEY_PAGE_INDEX = "pending_scan_page_index"
        const val TYPE_NEW_DOCUMENT = "new_document"
        const val TYPE_APPEND_PAGES = "append_pages"
        const val TYPE_REPLACE_PAGE = "replace_page"
    }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manual verification via "Don't keep activities"**

On-device: enable Developer Options → "Don't keep activities" (this forces Android to destroy `MainActivity` the moment it's backgrounded, simulating the process-death scenario without needing to wait for real memory pressure — this is the standard way to test this bug class without Robolectric/instrumented tests).
1. Open a saved multi-page document, tap "Replace page" on page 2, let the scanner UI open (this backgrounds `MainActivity`, which now gets destroyed immediately).
2. Complete the scan.
3. Confirm page 2 is actually replaced in the existing document — not a new document created.
4. Repeat for "Add pages" (confirm pages are appended to the existing document, not a new one created).
5. Turn "Don't keep activities" back off when done.
Report what you observe.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/papr/scanner/MainActivity.kt
git commit -m "Persist pending scan target across process death, fixing append/replace after backgrounding"
```

---

### Task 4: Full verification pass

Runs the complete build/test suite plus the full manual smoke test promised in the design spec's Success Criteria (broader than just the three fixes above — every screen/flow).

**Files:** none (verification only).

- [ ] **Step 1: Run the full Gradle check**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lint :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, no new lint errors introduced by this plan's changes, all existing unit tests pass.

- [ ] **Step 2: Install and manually smoke-test every screen/flow**

```bash
./gradlew :app:installDebug
```
Then on-device, walk through and report results for each:
1. Scan a new document (multi-page) → saves and opens detail screen.
2. Home screen: scroll the document grid, confirm thumbnails load and scrolling doesn't stutter.
3. Open a saved document → detail screen renders pages correctly (no black screen).
4. Rotate a page, move a page earlier/later, confirm both update instantly.
5. Add pages to a saved document (new in Task 1/3).
6. Replace a page in a saved document (new in Task 1/3).
7. Sign a page, then export — confirm the share sheet opens with a PDF.
8. Delete a single page from a multi-page document.
9. Delete a whole document from both the home screen and the detail screen.
10. Background the app mid-scan (see Task 3's "Don't keep activities" method) and confirm the app recovers correctly rather than crashing or losing the scan target.

- [ ] **Step 3: Report findings**

Summarize what worked and what didn't across the smoke test. Any newly-discovered issue that isn't one of this plan's three fixes gets logged for a future sub-project (per the design spec's decomposition), not fixed inline here unless it's trivially part of an existing task's scope.
