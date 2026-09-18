# File Merge — Design

## Context

Sub-project 6 of the decomposed Papr backlog (see
`docs/superpowers/specs/2026-09-12-stability-crash-fixes-design.md` for the
full decomposition). Unlike every prior sub-project, this is a genuinely new
feature, not a root-caused bug — the user's original request was just "no
file merge feature," with no further detail, so this design was built from
five rounds of clarifying questions rather than reading existing behavior.

Decisions made during brainstorming:
1. Merging produces a **new, persisted document** in the library (not a
   one-off export) — it behaves like any other scanned document afterward
   (rotate, reorder, sign, export, etc. all already work on it for free).
2. **Original source documents are kept**, untouched, after a merge.
3. Documents are selected via a **long-press multi-select mode** on the home
   screen (replacing today's long-press-to-delete-one shortcut — delete
   moves into the selection-mode action bar instead, so nothing is lost).
4. Merge order is set by a **dedicated reorder/confirm screen** (not just
   selection order) shown before the merge actually runs.
5. The merged document's **title is prompted for** on that confirm screen,
   pre-filled with a sensible default.
6. Merging works at the **whole-document level only** — no picking
   individual pages out of a source document. (You can still trim/reorder
   pages in the merged result afterward, since the detail screen already
   supports that.)

Current relevant state (verified by reading the code): `Document` has no
concept of "member documents" or provenance — a merged document is
indistinguishable from a freshly-scanned one once created, which is
intentional (it's meant to be a normal document afterward, not a special
type). `HomeScreen`'s `DocumentGridItem` currently uses `combinedClickable`
with `onLongClick` wired directly to a single-document delete-confirmation
dialog. `DocumentDetailScreen` already has a page-reorder pattern
(`movePage(page, direction)` with left/right arrow `IconButton`s) that the
merge confirm screen's document-reorder UI will mirror, rather than
introducing drag-and-drop. `MainActivity.handleNewDocument` already
establishes the exact "insert document → import/copy pages → generate
thumbnail → update document" shape the new merge operation will reuse.

Same environment constraints as prior sub-projects: no device/emulator, so
UI/interactive verification is handed off in a manual checklist.

## Architecture

### Data flow

A new `DocumentMerger` class (package `data/repository`, alongside
`DocumentRepository`) does the actual merge:

```kotlin
class DocumentMerger(
    private val documentRepository: DocumentRepository,
    private val appContext: Context,
) {
    suspend fun merge(documentIds: List<Long>, title: String): Long
}
```

For each document ID in the given order, its pages (already ordered by
`pageIndex`) get copied — real file copies, `File.copyTo()`, into the new
document's own directory (`FileUtils.documentDir`), following the same
`"page_${UUID.randomUUID()}.jpg"` naming `DocumentScannerManager` already
uses — with fresh, contiguous `pageIndex` values assigned across the whole
merged sequence. Each new `Page`'s `extractedText` is carried over directly
from its source page (OCR already ran on it; no need to re-run recognition).
After all pages are inserted, a thumbnail is generated from the first page
and the new document's `pageCount`/`thumbnailUri` are set — the same
sequence `MainActivity.handleNewDocument` already follows for a fresh scan.
Returns the new document's ID so the caller can navigate straight to it.

Real copies (not shared file references) mean the merged document is fully
independent afterward — rotating, deleting, or replacing a page in it can
never affect (or be affected by) the original source documents.

### Home screen selection mode

`HomeUiState` gains `isSelectionMode: Boolean` and
`selectedDocumentIds: List<Long>` (a *list*, not a set — order matters,
since it's the default merge order carried into the confirm screen).
`HomeViewModel` gains handlers for: long-press (enters selection mode,
selects the pressed document), tap-while-in-selection-mode (toggles
selection instead of opening the document), clearing selection, and bulk
delete of the selected documents (adapting the existing single-document
delete path).

`DocumentGridItem` gains `selectionMode`/`isSelected` parameters and renders
a checkbox overlay when in selection mode. `HomeScreen`'s `TopAppBar`
becomes contextual: in selection mode it shows a "N selected" title, a close
(✕) action to exit selection mode, a Delete action (enabled at 1+
selected — reuses the existing confirmation-dialog pattern, retargeted at N
documents), and a Merge action (enabled at 2+ selected, navigates to the new
confirm screen with the selected IDs).

### Merge confirm screen (new)

New route `Screen.MergeConfirm`, carrying the selected document IDs as a
comma-joined string nav argument (Navigation Compose doesn't support a
`List<Long>` argument type directly). A new `MergeConfirmViewModel` loads
those documents (preserving the given order) and exposes:
- The ordered list of documents (for display: thumbnail + title).
- Move-up/move-down actions per item (mirroring `DocumentDetailScreen`'s
  `movePage` pattern exactly — array-index swap, no new UI paradigm).
- A remove-from-merge action per item.
- The editable title field, pre-filled with a default built by joining the
  source titles (truncated if there are many/long titles — exact truncation
  rule is a plan-level detail, not a design-level one, since it's a
  mechanical string-formatting choice with only one reasonable shape once
  implemented).
- A confirm action that calls `DocumentMerger.merge(...)` and, on success,
  navigates to the new document's detail screen — replacing the confirm
  screen in the back stack, so pressing back from the new document goes to
  Home, not back to the confirm screen.

## Testing approach

Same constraint as every prior sub-project: no device/emulator, no
Robolectric. The default-title-generation logic (joining source titles) is
pure and gets a real JUnit test, following this project's established
pattern of extracting testable pure logic into a top-level function. The
reorder logic (move-up/move-down on a list) is also pure enough to test the
same way. Everything else (the actual file-copy merge operation, the
selection-mode UI, navigation) is verified by code reasoning during
implementation plus a manual on-device checklist at the end, same pattern as
every prior sub-project.

## Success Criteria

- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:lintDebug`, `:app:testDebugUnitTest` all pass.
- New unit tests cover the default-title-generation and reorder logic.
- Manual on-device checklist: long-press enters selection mode; tapping
  toggles selection and updates the count; Delete removes the selected
  documents (with confirmation) and originals stay intact when only some
  documents are selected; Merge with 2+ selected opens the confirm screen
  with documents in tap order; move-up/move-down and remove-from-merge work
  on that screen; confirming creates a new document whose pages are the
  source documents' pages in the chosen order, with a correct page count and
  thumbnail; the original source documents are untouched afterward; the new
  document's pages are independently editable (e.g. rotating a page in the
  merged document doesn't affect the source); back navigation from the new
  merged document returns to Home, not the confirm screen.

## Out of scope

Page-level (rather than whole-document) merge selection. Deleting or
archiving source documents automatically after a merge. The
confidential-marker stub and the broader app-wide wrap-up/testing-prep pass
the user mentioned — those are separate, already-identified next steps, not
part of this sub-project.
