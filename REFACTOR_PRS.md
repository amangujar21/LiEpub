# StoryWeaver — sequenced refactor PRs

> **Status (2026-07-11):** P1–P6 implemented in-tree. Gate: `./gradlew test` + `:app:assembleDebug` green.

Follow-up to the strict code review. Goal: **same product behavior**, dramatically simpler structure, correct layering (`PLAN.md` §3 / `AGENTS.md`).

**Rules for every PR**

1. **Tests first** (or same PR, red → green before drive-by cleanup).
2. **File allowlist** — do not touch paths outside the list without expanding the PR scope intentionally.
3. **Green gate before merge:**

   ```text
   ./gradlew test
   ./gradlew :app:assembleDebug
   ```

4. Prefer **sequential** PRs. Later PRs assume earlier ones merged. Do not start P4 until P1–P3 land.
5. No new product features in these PRs (no WorkManager, no import UI, no TOC drag-and-drop) unless listed.

**North-star session model** (introduced gradually):

```text
CreateSession
  resolved: ResolvedDraft          // authors, works, chapters, warnings
  selection: Set<WorkId>
  metadata: title, subtitle, curator, exportMode, epubStyle
  catalogView: AuthorCatalog?      // derived for filter/sort UI only
  phase / progress / failures
```

One authority for content; UI catalog is a **view**, not a second store.

---

## PR map

| PR | Title | Risk | Depends on |
|----|--------|------|------------|
| **P1** | Domain: single draft builder + fix chapter selection | Low | — |
| **P2** | Download path uses `downloadChapters` + concurrency | Medium | P1 |
| **P3** | Create use cases + thin ViewModel | Medium–High | P1–P2 |
| **P4** | App shell: update check + DI contracts | Medium | P3 |
| **P5** | EPUB package assembly unification (writer/merger) | High | P1–P3 |
| **P6** | Dead API cleanup + dual-model removal in UI state | Low–Med | P3 |

Optional later (out of scope here): WorkManager jobs, SAF export folder, import UI, TOC reorder UI, refresh fingerprints.

---

## P1 — Domain: one draft path, correct chapter IDs

### Intent

Delete dual TOC-construction mental models and the broken standalone chapter ID convention. Production path is only `buildFromResolved` (or a renamed single entry point). `selectedChapterIds` must match `toExportSnapshot`.

### Tests first

| Test | Module | Assert |
|------|--------|--------|
| `TocOperationsTest` — `buildFromResolved` multi-author series + standalone | `:core:model` | Node kinds, parent links, positions, titles |
| `TocOperationsTest` — `selectedChapterIds` for standalone | `:core:model` | IDs equal real `Chapter.id` from input list, **not** `chapter-for-$workId` |
| `TocOperationsTest` — `toExportSnapshot` selection matches `selectedChapterIds` contents | `:core:model` | Same chapter set for series + standalones |
| Keep existing `buildFromCatalog` tests green **or** rewrite them to call the single API | `:core:model` | No behavior loss |

### Implementation

1. Change `selectedChapterIds` to resolve standalones via `chapters` / draft nodes → work id → chapter entity (same rule as `toExportSnapshot`). Signature may become:

   ```kotlin
   fun selectedChapterIds(draft: CollectionDraft, chapters: List<Chapter>): Set<ChapterId>
   ```

2. Implement `buildFromCatalog` as a thin wrapper over `buildFromResolved` (or delete and update call sites/tests only).

3. Add `fun draftFromUi(...): CollectionDraft` helper **in domain** if useful for later PRs — optional; avoid if it just renames `buildFromResolved`.

4. Remove empty locked-children no-op in `validateHierarchy` (or implement a real rule + test). Prefer delete empty branch.

### File allowlist

```text
core/model/src/main/kotlin/com/storyweaver/core/model/TocOperations.kt
core/model/src/main/kotlin/com/storyweaver/core/model/Toc.kt          # only if signature needs types
core/model/src/test/kotlin/com/storyweaver/core/model/TocOperationsTest.kt
```

### Out of scope

ViewModel, EPUB, network, UI.

### Done when

- No synthetic `chapter-for-` IDs in production selection logic.
- One documented entry for “selection → CollectionDraft”.
- `./gradlew :core:model:test` green.

---

## P2 — Real concurrent download + cache fold

### Intent

Settings concurrency must affect downloads. Use canonical `LiteroticaSource.downloadChapters`. Delete sequential `for` download in Create.

### Tests first

| Test | Module | Assert |
|------|--------|--------|
| Fake `LiteroticaSource` / test double for download orchestration | prefer pure use-case later; for P2 can test helper | |
| **New** `ChapterDownloadCoordinatorTest` (JVM) | see placement below | Given N URLs, concurrency K, fake delays: max in-flight ≤ K; cache hits skip network; failures collected without aborting others |

**Placement options (pick one, keep pure JVM):**

- A) `feature/create/.../download/ChapterDownload.kt` + unit test in `feature/create` (needs test source set), or
- B) Prefer **domain/app-agnostic** helper in a small type next to where P3 will put use cases:

  ```text
  feature/create/src/main/kotlin/.../session/ChapterDownload.kt
  feature/create/src/test/kotlin/.../session/ChapterDownloadTest.kt
  ```

  If feature module has no unit-test wiring yet, add `test` source set in `feature/create/build.gradle.kts` (JVM or android unit test). Simplest path: put pure logic in `:core:model` only if it stays free of Android — **better: keep in feature create as pure Kotlin**.

Fake:

```kotlin
interface ChapterContentLoader {
    suspend fun loadCached(url: String): ChapterContent?
    suspend fun fetch(url: String): ChapterContent
}
```

Or wrap existing `LiteroticaSource` + cache lambdas without inventing too many ports.

### Implementation

1. Extract pure suspend function:

   ```kotlin
   suspend fun downloadSelectedChapters(
       chapters: List<Chapter>,
       concurrency: Int,
       loadCached: suspend (String) -> ChapterContent?,
       fetch: suspend (String) -> ChapterContent,
       onProgress: (done: Int, total: Int) -> Unit,
   ): DownloadOutcome // contents + failedUrls
   ```

   Internally: partition cache hit/miss → `coroutineScope` + `Semaphore` **or** call `source.downloadChapters` for misses only.

2. Replace CreateViewModel sequential loop + `retryMissing` loop to use the same helper.

3. Pass `state.concurrency` into the helper (coerce 1..6 as today).

4. Do **not** yet extract full use-case class (P3) unless trivial.

### File allowlist

```text
feature/create/build.gradle.kts                          # test deps only if needed
feature/create/src/main/kotlin/.../CreateViewModel.kt
feature/create/src/main/kotlin/.../session/ChapterDownload.kt   # new
feature/create/src/test/kotlin/.../session/ChapterDownloadTest.kt  # new
source/literotica/.../LiteroticaSource.kt                # only if downloadChapters API needs cache-aware tweak
```

Prefer **not** changing LiteroticaSource if the helper can compose cache + `downloadChapters(urls)`.

### Out of scope

ViewModel thinning beyond download; metadata; export.

### Done when

- Concurrency > 1 exercises parallel fetches in unit test (use a thread-safe counter of in-flight).
- Retry missing reuses the same helper.
- Manual smoke: Settings concurrency 1 vs 6 still completes export (timing optional).

---

## P3 — Use cases + thin CreateViewModel

### Intent

ViewModel maps intents ↔ UI state only. Business pipeline lives in explicit use cases with real repository interfaces (no optional null callbacks).

### Target shape

```text
feature/create/
  session/
    CreateSessionState.kt          # pure state / UiState reduction if desired
    AnalyzeUrls.kt
    DownloadAndExport.kt           # or split Download + Export
    SaveCollection.kt
    LoadSavedCollection.kt
    ChapterDownload.kt             # from P2
  CreateViewModel.kt               # thin
  CreateScreen.kt                  # unchanged behavior
```

**Interfaces (feature or app-facing ports):**

```kotlin
interface CollectionStore {
    suspend fun save(draft: CollectionDraft, authors: List<Author>, works: List<Work>, chapters: List<Chapter>, sourceUrls: List<String>)
}

interface ChapterCachePort {
    suspend fun getByUrl(url: String): ChapterContent?
    suspend fun putAll(contents: Map<ChapterId, ChapterContent>, urlById: Map<ChapterId, String>)
}

interface ExportHistoryPort {
    suspend fun record(collectionId: CollectionId, title: String, filename: String, incomplete: Boolean)
}

// Adapters in :app bind Room repos → these ports
```

### Tests first

| Test | Assert |
|------|--------|
| `AnalyzeUrlsTest` with fake `LiteroticaSource` | Merges multi-URL result into session fields; link errors preserved |
| `DownloadAndExportTest` with fakes | WAITING_FOR_DECISION on partial fail; COMPLETE on success; SEPARATE vs COMBINED calls writer correctly |
| `LoadSavedCollectionTest` | Restores selection from draft nodes; multi-author does not invent wrong single author catalog without data |

Use fakes, not Room/OkHttp.

### Implementation

1. Move draft construction to one private `sessionDraft(state)` using P1 APIs — call sites in use cases only.
2. Replace factory lambdas with required ports (non-null).
3. `CreateViewModel.factory` takes ports + source + writer.
4. `AppContainer` / `MainActivity` adapters: thin `{ collections.save(...) }` implementing interfaces — **interfaces live in feature create or a tiny `:core:domain` port package**; prefer `feature/create` ports to avoid new module unless needed.
5. Reduce `CreateUiState` duplication only as much as needed for compile; full dual-model removal is **P6**.

### File allowlist

```text
feature/create/src/main/kotlin/com/storyweaver/feature/create/**
feature/create/src/test/kotlin/com/storyweaver/feature/create/**
feature/create/build.gradle.kts
app/src/main/kotlin/com/storyweaver/app/di/AppContainer.kt
app/src/main/kotlin/com/storyweaver/app/MainActivity.kt   # factory wiring only
```

### Out of scope

MainActivity privacy/update extraction (P4). EPUB merger (P5). Removing catalog+lists dual fields completely (P6).

### Done when

- `CreateViewModel` has no direct multi-step export body > ~40 lines per intent (delegates).
- No `?.invoke` optional persistence — missing deps fail at composition.
- Feature unit tests cover analyze + download decision + export happy path.

---

## P4 — App shell: network update check + composition

### Intent

Activity does not own OkHttp or ad-hoc clients. Update check is a small component using shared policy with an **explicit allowlisted release host**.

### Tests first

| Test | Module | Assert |
|------|--------|--------|
| `AppUpdateCheckerTest` with MockWebServer or fake fetcher | `:core:network` or `:app` unit | Parses version JSON; same version → up to date; higher → message; HTTP error → non-critical string |
| Host policy test | `:core:network` | Release feed host allowed for update client only; Literotica client unchanged |

### Implementation

1. Extend host policy **narrowly**:

   - Either separate `createReleaseFeedClient()` allowing `raw.githubusercontent.com` (or pinned host from `AppUpdateInfo`),  
   - Or `HttpFetcher` + interceptor list that only that client uses.

2. `AppUpdateChecker.check(): String` (or sealed result) in app data or network.

3. Move biometric / privacy lock / age gate out of mega-composable if cheap:

   ```text
   app/.../ui/PrivacyGate.kt
   app/.../data/AppUpdateChecker.kt
   ```

   Keep behavior identical (fail-open when biometrics unavailable).

4. Remove raw `OkHttpClient` from `MainActivity`.

### File allowlist

```text
core/network/src/main/kotlin/com/storyweaver/core/network/**
core/network/src/test/**                                     # if/when added
core/model/src/main/kotlin/.../Preferences.kt                # AppUpdateInfo only if needed
app/src/main/kotlin/com/storyweaver/app/MainActivity.kt
app/src/main/kotlin/com/storyweaver/app/data/AppUpdateChecker.kt   # new
app/src/main/kotlin/com/storyweaver/app/ui/PrivacyGate.kt          # optional split
app/src/main/kotlin/com/storyweaver/app/di/AppContainer.kt
app/build.gradle.kts                                         # test deps if needed
```

### Out of scope

Create pipeline, EPUB.

### Done when

- Settings “Check for updates” still works with same UX strings (approx).
- No second unpolicy’d client constructed in UI code.
- Literotica allowlist still blocks non-Literotica on the story fetcher.

---

## P5 — EPUB: one package assembler

### Intent

Stop maintaining two ZIP/OPF pipelines. `EpubWriter` builds packages from a list of contributions; `EpubMerger` namespaces imports into the same assembler. Delete double-write + unzip hack.

### Tests first

| Test | Assert |
|------|--------|
| Existing `EpubWriterTest` / `EpubImportMergeTest` stay green | Structural validation OK |
| New assembler test: chapters only | Same spine order as current writer for a fixed snapshot |
| Merge test: downloaded + one import | Nested nav under imported book title; no path slip; validate() ok |
| Resource namespace stability | Imported `OEBPS/Text/ch1.xhtml` → unique prefix; internal links rewritten (whatever importer already guarantees) |

### Implementation sketch

```text
PackageAssembler
  inputs: metadata, style, cover, List<ContentUnit>
  ContentUnit = DownloadedChapter | ImportedSubtree
  output: Map<path, bytes> → zipEpub
```

- Move `zipEpub`, OPF/NCX/nav builders to shared internal types (package-private).
- `EpubWriter.write` = assembler(downloaded only).
- `EpubMerger.merge` = assembler(downloaded + imported), **no** intermediate full write for base (unless proven necessary).

Remove empty standalone branch in current writer while touching nav construction.

### File allowlist

```text
epub/src/main/kotlin/com/storyweaver/epub/**
epub/src/test/kotlin/com/storyweaver/epub/**
```

### Out of scope

Create UI import button; SAF; feature modules.

### Done when

- Single place defines mimetype/container/OPF.
- Merger does not call `writer.write` solely to unzip.
- All epub tests green; no public API break for `EpubWriteRequest` if avoidable (adapters ok).

---

## P6 — UI state dual-model removal + dead API cleanup

### Intent

`CreateUiState` holds one content authority. Delete unused/broken surface area that confuses readers.

### Tests first

- Update feature session tests from P3 for new state shape.
- `DuplicateDetection` tests if kept and wired; else delete type in this PR with grep-clean.

### Implementation

1. State shape:

   ```kotlin
   data class CreateUiState(
       val urlInput: String,
       val resolved: ResolvedDraft?,      // or authors/works/chapters once
       val selection: Set<WorkId>,
       val catalogView: AuthorCatalog?, // derived via pure function
       val searchQuery / sort / filteredCatalogView,
       val metadata...,
       val phase / progress / errors / export results / pending download...,
       val concurrency: Int,
   )
   ```

2. `catalogView = toCatalogView(resolved)` pure function in model or feature (multi-author: real multi section or flat list by author — **preserve current UI list behavior**).

3. Delete dead: unused `JobPhase` values only if nothing references; or leave enum and document. Prefer remove `CANCELLING`/`CANCELLED` if unused.

4. Grep-delete `DuplicateDetection` **or** wire a one-line warning into export prep (minimal) — prefer wire tiny warning list to match PLAN if cheap.

5. `loadSaved` must not invent `chapter-for-` IDs; fail or use persisted chapters only.

### File allowlist

```text
core/model/src/main/kotlin/com/storyweaver/core/model/Entities.kt   # filter/view helpers, dead delete
core/model/src/test/**
feature/create/src/main/**
feature/create/src/test/**
feature/create/src/main/.../CreateScreen.kt                         # only if state field renames
```

### Done when

- No `authors.ifEmpty { catalog... }` fallbacks.
- Grep clean for `chapter-for-`.
- UI behavior smoke: analyze author URL → select → export unchanged.

---

## Dependency graph (merge order)

```text
P1 ──► P2 ──► P3 ──► P4
              │
              └──► P6
P1 ──► P3 ──► P5     (P5 can parallel P4 after P3; needs stable ExportSnapshot only)
```

Recommended calendar order: **P1 → P2 → P3 → P4 → P6 → P5**  
(P5 last because highest regression risk on binary EPUB output; isolate when session layer is stable.)

---

## Explicitly deferred (do not sneak into P1–P6)

| Item | Why defer |
|------|-----------|
| WorkManager + process-death recovery | New product infrastructure |
| SAF default folder / Save as | Product + Android contracts |
| Import EPUB UI + appendImportedBook wiring | Needs P5 + UX |
| TOC reorder / rename UI | Domain ready; separate feature PR |
| Series refresh fingerprint UX | Needs product design |
| Normalized Room tables (vs JSON blobs) | Migration-heavy |
| First git commit / CI | Repo hygiene; do before or after P1 |

---

## Per-PR checklist (copy into PR body)

```markdown
## Intent
-

## Tests added
-

## Allowlist respected
- [ ] No files outside allowlist (or listed exceptions)

## Gate
- [ ] ./gradlew test
- [ ] ./gradlew :app:assembleDebug

## Behavior unchanged
- [ ] Analyze / select / download / export happy path
- [ ] Partial failure retry / export without
- [ ] Save + open from Library
- [ ] Settings concurrency (after P2) actually affects downloads
```

---

## Success metric (after P6)

| Before | After |
|--------|--------|
| CreateViewModel owns full pipeline + optional callbacks | ViewModel + 3–5 use cases + ports |
| Sequential download; concurrency unused | `downloadChapters` / shared helper honors K |
| Catalog + lists dual store + `ifEmpty` | Single `ResolvedDraft` + derived view |
| Update check ad-hoc OkHttp in Activity | Policy’d checker in data/network |
| Writer + merger two ZIP stacks | One assembler |
| Synthetic `chapter-for-` IDs | Single chapter identity model |

This is the minimum set that converts the review blockers into mergeable slices without boiling the ocean.
