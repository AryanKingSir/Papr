# Stability & Crash Fixes — Design

## Context

Papr (Android document scanner, Kotlin/Compose) has a large backlog of reported
issues: random freezes/stutters/crashes, visual glitches/black screens, and
several concrete feature/bug gaps (signature placement, page-size consistency,
compression targets, missing document editing, weak search, broken OCR
placement, export naming, branding polish, build warnings, missing merge
feature). That full list is too large for one design, so it's being
decomposed into independent sub-projects, each with its own
spec → plan → implementation cycle. This is sub-project 1: **stability**,
chosen first because it's foundational — an app that freezes/crashes
unpredictably is hard to test, and a broken build blocks everything else.

No crash logs, ANR traces, or crash-reporting tool (Crashlytics, Play Console)
exist for this app. The user has not seen a specific reproducible pattern —
freezes/crashes/black screens are reported as "everywhere, no clear pattern."
The user explicitly declined adding new crash/ANR logging infrastructure for
this pass; verification is code review + manual smoke testing.

## Discovery: the build is currently broken

Running `./gradlew :app:compileDebugKotlin` on the current working tree (git
status shows several modified-but-uncommitted files, atop commit `a769c81`)
fails:

```
PaprNavHost.kt:54 No parameter with name 'onAddPagesClick' found.
PaprNavHost.kt:55 No parameter with name 'onReplacePageClick' found.
```

Investigation showed this isn't incidental breakage — a prior session had
already built most of a "add pages to a saved document" / "replace a page"
feature (this is the user's requested item: no way to edit a document after
save):

- `MainActivity.kt` has a complete `ScanTarget` sealed interface
  (`NewDocument` / `AppendPages` / `ReplacePage`) with working handlers
  (`handleAppendPages`, `handleReplacePage`) that launch the ML Kit scanner
  and persist results.
- `DocumentRepository` already supports the needed operations
  (`insertPages` for append, `updatePage`/`deletePage` for replace).
- `PaprNavHost` already threads `onAddPagesClick` / `onReplacePageClick`
  callbacks down to where `DocumentDetailScreen` is instantiated.

The only missing piece: `DocumentDetailScreen.kt` was never updated to accept
those two callback parameters or expose UI (buttons) to trigger them. That
gap is the entire compile error.

**Decision (user-approved):** finish this wiring as part of restoring the
build, rather than stubbing it out. It fixes compilation and delivers the
"edit after save" feature in one pass, since the backend work is already
done.

## Scope

### 1. Restore compilation by finishing document-editing UI wiring

- Add `onAddPagesClick: (documentId: Long) -> Unit` and
  `onReplacePageClick: (documentId: Long, pageId: Long, pageIndex: Int) -> Unit`
  parameters to `DocumentDetailScreen`.
- Add UI affordances consistent with the existing page-action row (rotate /
  sign / delete / move icons) in `DocumentDetailScreen.kt`:
  - An "Add pages" action (top-level, not tied to a specific page — e.g. in
    the top app bar or as an item alongside export) that calls
    `onAddPagesClick(documentId)`.
  - A "Replace page" action tied to the current page (in the existing page
    action row) that calls
    `onReplacePageClick(documentId, currentPage.id, currentPage.pageIndex)`.
- No changes to `MainActivity.kt`, `PaprNavHost.kt`, `DocumentRepository`, or
  `DocumentDetailViewModel` — that plumbing already exists and compiles once
  the screen's signature matches.

### 2. Static stability audit

Systematic sweep of the codebase (not limited to files already touched) for
the standard classes of Android instability, since there's no crash log to
chase a specific bug:

- **Main-thread-blocking calls**: File I/O, `Bitmap` decode/compress/create,
  Room DAO access, or other blocking calls not wrapped in `Dispatchers.IO` /
  not `suspend`. Recent commits (`Make FileUtils bitmap operations suspend +
  IO-dispatched`, `Wrap PdfEngine.generatePdf ... withContext(Dispatchers.IO)`)
  show this was a known problem class already being fixed piecemeal — the
  audit checks whether it's been fixed everywhere (scanner import path,
  billing, repository, thumbnail generation, any remaining call site).
- **Bitmap/memory handling**: missing `recycle()`, avoidable full-resolution
  decodes, any path that could OOM on a large multi-page scan or a
  high-resolution camera capture.
- **Coroutine scope misuse**: leaked launches outside `viewModelScope`/
  structured concurrency, unguarded exceptions inside a `launch` that would
  crash the process instead of surfacing through the existing `_errors`
  `SharedFlow` pattern, any `GlobalScope` usage.
- **Compose lifecycle / navigation issues**: state read or mutated unsafely
  across recomposition, `LaunchedEffect` key mistakes, navigation/back-stack
  timing that could render a composable before its backing `StateFlow` has
  emitted (a plausible cause of a "black screen").
- **Defensive gaps in hot paths**: unguarded `!!`, unchecked nullable access,
  or unhandled exceptions along scan → save → detail → export, since a single
  uncaught exception in any of these crashes the app.

Each finding is fixed surgically in place — no unrelated refactors or
"improvements," consistent with the project's existing CLAUDE.md guidelines.
Pre-existing dead code or unrelated issues noticed along the way are reported,
not fixed, unless they're part of a stability finding.

### Out of scope (belongs to later, already-planned sub-projects)

- Signature placement *position/accuracy* on the exported PDF, multi-page
  PDF page-size consistency, and compress-to-target-size *accuracy* — reading
  the current WIP code, all three already have real implementations
  (`BitmapPdfCompiler.uniformPageSizePx`, per-page `recompress` targeting,
  `DocumentDetailViewModel.buildSignaturePlacement`). Whether they actually
  produce correct output needs dedicated testing/verification, which is
  sub-project 2 (PDF pipeline correctness), not this pass.
- OCR auto-run and accuracy, search, export filename, branding/UI polish,
  build warnings (targetSdk/compileSdk, `isShrinkResources`), file merge,
  confidential-marker stub — all separate sub-projects.

## Success Criteria

- `./gradlew :app:compileDebugKotlin` and `./gradlew :app:assembleDebug`
  succeed.
- `./gradlew lint` and `./gradlew :app:testDebugUnitTest` pass (existing
  tests; no new tests are being added in this pass since there's no new
  business logic beyond UI wiring already covered by existing ViewModel/
  Repository behavior).
- Manual smoke test of every screen/flow, installed on-device or emulator:
  scan → save (new document), home grid scroll, open a document, rotate/
  move/delete a page, add pages to a saved document, replace a page, sign a
  page, export, delete a document. Findings (what worked, what didn't) are
  reported back, since this is the actual acceptance test in the absence of
  crash telemetry.
- Each stability fix is accompanied by a one-line note identifying the
  concrete failure mode it addresses (e.g., "moved X off main thread — was
  blocking the UI thread during Y").

## Risks / Notes

- Without crash logs, some findings from the static audit are informed
  guesses about what *could* cause the reported symptoms, not confirmed root
  causes. The manual smoke test at the end is how we catch what static
  review misses — if freezes/crashes persist after this pass, the next step
  would be adding targeted logging (previously declined, can be revisited).
- This pass intentionally does not add crash/ANR reporting infrastructure,
  per user decision — so future regressions in this area will again rely on
  manual reports unless that decision is revisited later.
