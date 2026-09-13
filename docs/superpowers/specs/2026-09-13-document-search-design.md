# Document Search — Design

## Context

Sub-project 5 of the decomposed Papr backlog (see
`docs/superpowers/specs/2026-09-12-stability-crash-fixes-design.md` for the
full decomposition). The user's original request: "i want better search with
features like being able to search for a date rather than just file name,
also with OCR people cm searh with particular file contents so for exapmle
if i have a licsnce but the name is just 7sept... and i dont know the date i
want to be able to search as drivers license and it will scan with OCR and
give results, but if there are multiple docs proirity goes to name then date
then ocr."

This builds directly on sub-project 4 (OCR rework), which made
`Page.extractedText` actually get populated after a scan — this sub-project
is the first thing that reads it.

Two clarifying rounds with the user settled the scope precisely:
- "Search by date" means a **separate date-range picker**, not typing a date
  into the text search box.
- The "name, then date, then OCR" priority the user described applies to
  **text search only** (title vs. OCR-content matches) — the date picker is
  an independent filter, like the existing folder filter, not a ranking
  tier.
- A visual "matched in scanned text" indicator is wanted, so a result that
  only matched via OCR content is distinguishable from a name match.

Current state (verified by reading the code): `HomeViewModel` combines
`documentRepository.getAllDocuments()` with a `searchQuery` and
`selectedFolder`, filtering in-memory by
`title.contains(query, ignoreCase = true)` — title-only, no ranking, no
date, no content search. `Document` has `createdAt`/`updatedAt` (epoch
millis) but no other date concept. There is no full-text-search (FTS)
database setup — everything is plain Room queries plus in-memory Kotlin
filtering over reactive `Flow`s, the same pattern `selectedFolder` already
uses.

Same environment constraints as prior sub-projects: no device/emulator, so
UI/interactive verification is handed off in a manual checklist.

## Architecture

Extends the existing reactive-`Flow`-plus-in-memory-filter pattern rather
than introducing new search infrastructure (no FTS, no new dependency) —
consistent with how folder filtering already works, and an appropriate
level of complexity for a personal-document-scanner's data volume.

- `DocumentRepository` gains `getAllPages(): Flow<List<Page>>`, backed by a
  new `PageDao` query (`SELECT * FROM pages`) — needed because content
  search has to look across every document's pages, not just one document's
  pages (the only granularity `getPagesForDocument` supports today).
- `HomeViewModel.uiState` combines one more source (`getAllPages()`)
  alongside the existing `getAllDocuments()`, `searchQuery`,
  `selectedFolder`, and a new `selectedDateRange` state.
- For each document, when `searchQuery` is non-blank: compute whether its
  title matches (case-insensitive substring, same as today) and, if not,
  whether *any* of its pages' `extractedText` matches. A document with
  neither is excluded from the results entirely. A document that matched
  only via content gets a flag threaded through to the UI for the "matched
  in scanned text" badge.
- Sort key: title matches before content-only matches; within each tier,
  the existing order is kept (most-recently-updated first — already how
  `DocumentDao.getAll()` sorts).
- `selectedDateRange` (a nullable start/end epoch-millis pair) filters
  documents by `createdAt` falling within the range — independent of, and
  combinable with, the text search and folder filter, exactly like folder
  filtering combines with text search today.

## UI

- A calendar icon button near the search bar opens a Material3
  `DateRangePickerDialog` (already available via this project's Compose
  BOM — no new dependency). Confirming a range sets `selectedDateRange`;
  the dialog also has its own clear action.
- When a date range is active, it's shown as a small clearable chip (same
  visual language as the existing folder `FilterChip`s), so it's obviously
  active and easy to undo.
- `DocumentGridItem` gains a small "Matched in scanned text" badge/label,
  shown only when that specific document's result came from content, not
  its name, and only while a search query is active.
- The search text field itself (`DocumentSearchBar`) doesn't change — same
  plain text input as today, driving the same `searchQuery` state.

## Success Criteria

- `./gradlew :app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:lintDebug`, `:app:testDebugUnitTest` all pass.
- The document-vs-content ranking/matching logic (given a query, a title,
  and a list of page texts, determine match tier) is pure enough to unit
  test and gets real tests, following this project's established pattern
  (pure logic extracted where the surrounding class is otherwise
  Android-framework-dependent).
- Manual on-device checklist: search by a document's exact title (ranks as
  a name match, no badge); search by a word only present in a scanned
  page's OCR'd text, not the title (shows up, tagged as a content match);
  a query matching both a title and a different document's content shows
  the title match first; the date range picker filters the grid correctly
  and combines sensibly with an active text search and/or folder filter;
  clearing the date range restores the full list.

## Out of scope

File merge and the confidential-marker stub — separate, already-identified
sub-projects. No changes to the folder filter itself (only how it composes
with the two new filters). No fuzzy/typo-tolerant matching — same exact
case-insensitive substring matching the existing title search already uses,
just applied to more fields.
