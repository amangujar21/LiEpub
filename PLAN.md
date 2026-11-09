# StoryWeaver — Detailed Implementation Plan

## 1. Product definition

StoryWeaver is a lightweight, native Android application for downloading public Literotica stories and series as EPUB files and combining downloaded works with existing DRM-free EPUBs. All fetching, parsing, storage, EPUB generation, and merging happen locally on the device. No backend, account system, advertising, analytics, or remote content processing is used.

The first release targets Android 9 (API 28) and newer. It is distributed as a sideloaded APK and may offer a manual, user-initiated update check against a public release feed.

### Core outcomes

1. Accept an author works page, series page, individual story/chapter page, or multiple Literotica links.
2. Resolve each link into canonical authors, standalone works, series, and ordered chapters.
3. Let the user search, select, deselect, and reorder the resulting book structure.
4. Download text-only story content quickly with bounded parallelism and safe retry behavior.
5. Export either one combined EPUB or one EPUB per selected work.
6. Import and combine valid, DRM-free EPUB 2 or EPUB 3 files without flattening their internal TOCs.
7. Save reusable collections locally and refresh saved series for newly published chapters.

### Explicit version-one boundaries

- Publicly accessible Literotica pages only.
- No Literotica login, cookie import, CAPTCHA bypass, regional restriction bypass, or DRM removal.
- No built-in EPUB reader; completed books open in an external reader.
- No automatic background polling for story or app updates.
- No AI-generated covers or other online cover service.
- No internal rearrangement of chapters inside imported EPUBs.
- Imported EPUBs participate only in Combined Book mode.

## 2. Approved user experience

### Primary navigation

- **Create:** Add links or imported EPUBs, inspect detected content, arrange the TOC, configure the cover and metadata, and export.
- **Library:** Reopen collections, check sources for updates, add or remove content, and re-export.
- **Settings:** Theme, optional Dynamic Color, default output folder, download concurrency, EPUB style, cache management, privacy mode, and manual app update check.

### Link-entry paths

- Paste one or more URLs into Create.
- Receive a URL through Android's text share intent.
- Add more links to an existing draft.
- Normalize URLs, follow safe redirects within the supported host, canonicalize them, and remove exact source duplicates.
- Analyze all valid links while displaying per-link progress and non-blocking errors.

### Source behavior

- **Author URL:** Show a searchable, multi-select catalog. Do not immediately download every work.
- **Series URL:** Select every ordered part by default.
- **Story/chapter URL:** If it belongs to a series, resolve and select the complete series by default; otherwise select the standalone work.
- **Multiple URLs:** Merge results into one draft and deduplicate canonical works and chapters.
- **Continuation pages:** Follow explicit story-page continuation links and combine their content into the same chapter.

### Author catalog

- Group entries into Series and Standalone sections.
- Expand a series to display its ordered chapters.
- Show title, content type, chapter count, category, publication date, and selection state.
- Support search and sorting by site order, title, newest, and oldest.
- Provide Select all and Clear selection actions.
- Selecting a series selects all chapters; advanced controls allow chapter-level overrides.
- Avoid ratings, view counts, thumbnails, and other visual clutter.

### Book arrangement

The editable downloaded-content tree is:

```text
Author
├── Standalone Story
└── Series Title
    ├── Chapter 1
    ├── Chapter 2
    └── Chapter 3
```

- Authors, standalone works, series, and downloaded chapters are reorderable within valid hierarchy boundaries.
- An imported EPUB is an expandable but internally locked subtree beneath its book title.
- Imported books can be renamed and reordered as whole sections.
- Likely duplicates are shown before export.
- The user must approve the final structure before downloads begin.

### Export modes

#### Combined Book

- Produce one EPUB from every selected downloaded work and imported EPUB.
- Use Author as the top-level grouping for downloaded works.
- Give each author and series a clean divider page.
- Keep a standalone story as a direct readable TOC entry rather than adding a pointless Chapter 1 level.
- Preserve each imported book's internal TOC beneath a top-level book entry.

#### Separate Books

- Produce one EPUB per selected standalone story or series.
- Process all outputs as one observable batch.
- Do not include imported EPUBs in this mode.

### Cover and metadata

- Generate the cover locally from a fixed, minimal template using typography, a restrained geometric motif, and a muted burgundy accent.
- Provide editable book title, subtitle, displayed author/curator, accent color, and output filename.
- Derive useful defaults such as `Selected Stories — shiprat` or `Collected Stories — 3 Authors`.
- Generate the final raster cover at export time in an EPUB-reader-friendly aspect ratio and resolution.
- Do not use Literotica branding or imply official affiliation.

### Visual language

- Modern editorial style: crisp geometry, precise spacing, restrained elevation, and typography-led hierarchy.
- Warm off-white and charcoal light theme; near-black and soft ivory dark theme; muted burgundy accent.
- No ornamental book styling, gradients, glass effects, dense card grids, or decorative story imagery.
- Use moderate corner radii, not pill-shaped containers everywhere.
- Keep a consistent StoryWeaver palette by default; make Android Dynamic Color optional.
- Respect large text, screen readers, touch-target sizing, high contrast, and reduced-motion settings.

## 3. Proposed technical architecture

### Platform and principal libraries

- Kotlin.
- Jetpack Compose and Material 3 for UI.
- Navigation Compose for the three primary destinations and nested flows.
- Coroutines and Flow for asynchronous work and observable state.
- A lifecycle-aware dependency injection framework.
- OkHttp for HTTP, redirects, caching headers, cancellation, and bounded connection pooling.
- Jsoup for resilient HTML parsing and sanitization.
- Room for collections, normalized source metadata, cache references, and export history.
- WorkManager with foreground progress for long downloads and export jobs.
- Android Storage Access Framework for output folders and imported EPUB references.
- AndroidX Biometric plus device credential fallback for optional Privacy mode.

Pin exact dependency versions only when the project is scaffolded, after checking current stable Android tooling compatibility.

### Module layout

Use a small multi-module structure that isolates volatile site parsing and complex EPUB code without fragmenting the application excessively:

```text
:app
:core:model
:core:network
:core:database
:feature:create
:feature:library
:feature:settings
:source:literotica
:epub
```

- `:app`: Application composition, navigation, theme, share intent, dependency graph.
- `:core:model`: Pure domain types and validation rules.
- `:core:network`: HTTP policy, retry logic, host validation, response limits, and diagnostics.
- `:core:database`: Room entities, migrations, DAOs, and repository implementations.
- Feature modules: Screen state, use cases, and Compose UI.
- `:source:literotica`: URL classification, catalog parsing, series resolution, chapter extraction, and fixtures.
- `:epub`: EPUB reading, resource rewriting, cover rendering, package construction, merging, and validation.

If build overhead becomes disproportionate, keep the same package boundaries in fewer Gradle modules; do not merge the Literotica parser or EPUB engine directly into UI code.

### Layering rule

```text
Compose UI
    ↓ intents / observable state
Feature ViewModels and use cases
    ↓ domain interfaces
Repositories and job coordinator
    ↓
Literotica adapter | Room | EPUB engine | Android document storage
```

UI code must not parse HTML, manipulate ZIP entries, perform direct HTTP requests, or retain raw document streams.

## 4. Domain model

Use stable internal IDs independently of mutable titles or list positions.

### Principal entities

- `SourceUrl`: Submitted URL, canonical URL, detected source type, validation result.
- `Author`: Stable local ID, site username/identifier, display name, canonical works URL.
- `Work`: Stable local ID, author ID, title, description, category, publication date, canonical URL, and type (`STANDALONE` or `SERIES`).
- `Chapter`: Stable local ID, work ID, site part identifier, title, order key, canonical URL, publication date, cached revision fingerprint.
- `ContinuationPage`: Chapter ID, canonical page URL, page order.
- `ImportedBook`: Persisted document URI, package identifier, title, creator, language, cover metadata, format version, content fingerprint, availability status.
- `TocNode`: Stable ID, parent ID, node kind, referenced entity ID, display title, position, selection state, locked-children flag.
- `Collection`: Title, subtitle, curator, export mode, EPUB style, accent choice, created/modified timestamps.
- `CachedChapter`: Chapter ID, normalized XHTML path or database payload reference, content hash, source fingerprint, fetched timestamp, last-referenced timestamp.
- `ExportJob`: Collection snapshot, output mode, status, progress counts, failure list, timestamps, destination URIs.
- `DiagnosticEvent`: Local-only structured event without story text.

### Invariants

- A series must contain at least one ordered chapter.
- Each downloaded chapter belongs to exactly one work.
- A standalone work is a leaf TOC entry even if its web content spans continuation pages.
- Canonical source URLs are unique within a collection.
- A collection tree cannot contain cycles or invalid parent-child combinations.
- Imported-book child nodes retain their internal order in version 1.
- An export operates on an immutable collection snapshot so edits cannot corrupt a running job.

## 5. Literotica source adapter

### Supported URL classifier

Recognize and normalize at minimum:

- `/authors/{author}/works/stories`
- `/authors/{author}` when it can resolve to the works page
- `/series/se/{seriesId}`
- `/s/{storySlug}`

Rules:

- Require HTTPS after redirects.
- Restrict redirects and requests to explicitly supported Literotica hosts.
- Remove tracking parameters and fragments.
- Preserve only parameters known to control catalog pagination or ordering.
- Reject unsupported paths with a precise message.

### Parser design

- Implement page-specific parsers behind a `LiteroticaSource` interface.
- Prefer structural and semantic signals: canonical URLs, headings, article elements, series links, ordered part labels, pagination controls, and metadata.
- Avoid depending on generated class names or absolute DOM paths.
- Return a parse result plus warnings instead of throwing away all successfully parsed content.
- Keep raw HTML out of the database after parsing.
- Place sanitized, minimal HTML fixtures under test resources; do not commit full copyrighted stories.

### Resolution pipeline

1. Validate and classify all submitted URLs.
2. Fetch author/series/story metadata with limited concurrency.
3. Resolve story membership through the site's explicit series link where present.
4. Fetch the series page to obtain canonical series title and complete ordered parts.
5. Merge duplicate authors, works, and chapters by canonical identifiers.
6. Return a normalized catalog/draft tree with warnings and unavailable items.

Do not infer a series solely from similar titles such as `Ch. 02`; title heuristics may be a warning-only fallback when the site exposes no explicit grouping.

### Chapter extraction

Retain:

- Title and author.
- Publication date and category.
- Story description.
- Author notes.
- Story text, paragraph boundaries, headings, emphasis, meaningful dividers, block quotations, and ordinary hyperlinks.

Remove:

- Images from newly downloaded Literotica content.
- Ads, navigation, ratings, favorites, views, comments, sharing controls, scripts, forms, tracking elements, and unrelated recommendations.

Sanitize output into a strict XHTML-safe intermediate representation. Resolve allowed hyperlinks to absolute HTTPS URLs and remove unsafe schemes and event attributes.

### Politeness, performance, and failures

- Default to six concurrent chapter requests; expose a lower configurable setting.
- Use separate, lower concurrency for catalog discovery if one page fans out into many metadata requests.
- Respect server throttling and `Retry-After` where available.
- Apply jittered exponential backoff to transient timeouts, connection resets, 429 responses, and eligible 5xx responses.
- Do not aggressively retry authentication, access-denied, removed-content, or parse-contract failures.
- Cap response sizes and enforce connect/read/job timeouts.
- Cancel outstanding work promptly when the user cancels a job.
- Treat individual item failure as recoverable so the rest of the queue can finish.

## 6. Download and export orchestration

### State machine

```text
DRAFT
  → ANALYZING
  → READY
  → DOWNLOADING
  → WAITING_FOR_DECISION (only when items failed)
  → BUILDING
  → VALIDATING
  → SAVING
  → COMPLETE

Any running state → CANCELLING → CANCELLED
Any unrecoverable state → FAILED
```

Persist enough job state to recover after process death. Never retain live streams, activities, or Compose state in a worker.

### Failure decision

When downloads complete with missing chapters:

- Display every missing item and its reason.
- Offer **Retry missing** and **Export without them**.
- Mark intentionally incomplete exports in the local export record.
- Never silently build an incomplete EPUB.

### Progress

- Show analysis, download, build, validation, and save as distinct phases.
- Track completed/total works and chapters rather than using a fake time-based progress bar.
- Continue long work with a foreground notification while the screen is off.
- Privacy mode notifications contain counts and generic status only.

## 7. EPUB engine

### Generated EPUB requirements

- EPUB 3 package with a standards-compliant navigation document.
- EPUB 2-compatible NCX fallback.
- Reflowable XHTML content.
- OCF `mimetype` entry first and uncompressed.
- Correct manifest, spine, metadata, unique identifier, media types, relative paths, and UTF-8 encoding.
- Cover image plus appropriate cover metadata/properties.
- Language metadata derived from the collection default, editable before export if necessary.
- Modern, Classic, and Minimal stylesheets with no embedded font files.
- Internal divider/title pages for authors and series.
- Stable generated filenames and IDs independent of display punctuation.

### TOC mapping

- Combined books: collection → author/imported book → work → chapters as applicable.
- Standalone story: TOC leaf linked directly to its XHTML content.
- Series: parent entry linked to a series divider, with ordered chapter children.
- Imported book: parent entry linked to an imported title/cover page when possible, preserving the imported TOC below it.
- Separate series book: series title at the book level, chapters at the first visible TOC level.

### EPUB import

1. Receive a persisted document URI through the system picker.
2. Confirm the source is a ZIP-based EPUB and safely inspect entries.
3. Enforce ZIP-slip protections, decompressed-size limits, entry-count limits, and compression-ratio limits.
4. Read `META-INF/container.xml`, locate the OPF, and parse EPUB 2 or EPUB 3 package metadata.
5. Detect unsupported encryption/DRM. Allow only standards-defined non-DRM obfuscation that the engine explicitly supports.
6. Read the manifest, spine, navigation document and/or NCX, guide/landmarks, fallback chains, and resource relationships.
7. Produce an immutable imported-book model and content fingerprint.

### EPUB merging

- Allocate a unique namespace/directory for every imported book.
- Copy all permitted imported resources, including images, fonts, CSS, SVG, and media.
- Rewrite relative links in XHTML, SVG, CSS `url(...)`, navigation documents, and other supported resources to their new namespaced paths.
- Generate collision-free manifest IDs and package paths.
- Preserve linear spine order and the imported internal TOC hierarchy.
- Exclude or safely neutralize unsupported active/scripted content and report the compatibility change before export.
- Generate one new outer package, navigation document, NCX, cover, and collection metadata.
- Keep imported covers/assets within their imported section; the combined EPUB receives a new StoryWeaver cover.

### Validation

Run two validation levels:

1. Fast on-device structural validation before saving: ZIP layout, required files, XML parsing, manifest/spine references, navigation targets, duplicate IDs/paths, and broken internal links.
2. Development/CI validation with the official EPUBCheck CLI against generated and merged golden fixtures.

Write to a temporary app-private file, validate it, then copy to the chosen document destination. Delete temporary artifacts on cancellation or terminal failure.

## 8. Duplicate detection

### Downloaded sources

- Deduplicate automatically using canonical author, series, work, and chapter identifiers/URLs.
- If multiple submitted URLs resolve to the same series, keep one series and merge selection intent.

### Imported EPUBs

Calculate and compare:

- Package unique identifier when present.
- Normalized title/creator/language.
- Spine/content hashes after excluding volatile metadata.
- Whole-file hash as an exact duplicate signal.

Show likely matches as warnings and let the user select the retained copy. Never automatically delete or omit an imported book.

For a possible match between a downloaded work and content inside an imported EPUB, show both sources and require an explicit keep choice.

## 9. Persistence and cache

### Room data

Persist:

- Collections and their ordered TOC snapshots.
- Normalized source metadata and canonical URLs.
- Cached chapter references and hashes.
- Imported document URI references and metadata, not duplicate imported files.
- Export history, destination URIs, status, and incomplete-export flags.
- Settings that benefit from structured migration.

Use DataStore for small preferences such as theme, Dynamic Color, default concurrency, EPUB style, privacy mode, and update-check preference.

### Cache policy

- Store sanitized, cleaned chapter content in app-private storage.
- Reuse unchanged cached content for offline and near-instant re-export.
- Associate cache entries with a source/revision fingerprint.
- Track last reference time.
- Remove unreferenced entries after 30 days by default.
- Provide cache size and Clear cache in Settings.
- Keep content referenced by saved collections unless the user explicitly clears all cached content.
- Handle cleared cache gracefully by marking affected collections as requiring download.

### Document permissions

- Persist Storage Access Framework permissions for the default output folder and imported EPUBs.
- Detect revoked, moved, or deleted document references.
- Prompt the user to relink an unavailable imported EPUB rather than losing the collection entry.

## 10. Saved collections and refresh

- Reopening a collection restores its last approved TOC, metadata, cover settings, style, and source references.
- **Check for updates** re-fetches source metadata only when requested.
- Match chapters by canonical IDs, not by title or list position.
- Highlight newly discovered chapters and allow the user to include them.
- If an existing chapter fingerprint changes, flag it as changed; do not silently replace cached content.
- Removed or unavailable source chapters remain in the collection if cached, with a source-unavailable warning.
- Re-export creates a new export record and never silently overwrites an existing file.

## 11. File handling

- Let the user choose and persist a default output folder through Android's folder picker.
- Before export, show the proposed sanitized filename.
- Offer **Save**, **Save as**, and **Share**.
- On collision, offer Replace or Create numbered copy; never overwrite silently.
- After success, offer Open in reader, Share EPUB, and View in folder where supported.
- Batch Separate Books mode reports per-book destinations and failures.
- Sanitize filenames without changing the EPUB's human-readable internal metadata.

## 12. Privacy and security

### Privacy defaults

- No analytics, advertising SDK, remote crash reporting, or telemetry.
- No story text, author selections, library contents, or imported files leave the device except through explicit user sharing.
- Show a one-time 18+ acknowledgement and personal-use/responsibility notice.
- Use neutral application branding and do not imply Literotica affiliation.

### Optional Privacy mode

- Gate app entry with biometrics or device credentials.
- Apply Android secure-window behavior to hide recent-app previews and screenshots while enabled.
- Use title-free, author-free foreground notifications.
- Ensure deep links/share intents wait behind the privacy gate before revealing analyzed content.

### Network and parser security

- Permit network requests only to an allowlist of supported HTTPS hosts.
- Revalidate host and scheme after redirects to prevent SSRF-style redirect abuse.
- Do not execute downloaded JavaScript.
- Sanitize all HTML before converting it to XHTML.
- Apply strict time, size, nesting, XML entity, and ZIP resource limits.
- Avoid logging story bodies or imported document content.

### Diagnostic export

Include only:

- App and parser versions.
- Device/Android compatibility information.
- Canonical URLs involved.
- HTTP status and timing summaries.
- Parser stage and missing-selector/contract identifiers.
- Exception class and sanitized stack information.

Exclude story text, cookies, credentials, imported filenames when privacy mode is enabled, and all imported EPUB content.

## 13. Manual app updates

- Add a user-initiated Check for app updates action in Settings.
- Query a pinned HTTPS public release endpoint.
- Compare semantic versions locally.
- Show release version, date, and notes summary.
- Open the release page only after the user selects it.
- Do not silently poll, download APKs, request package-install permission, or perform installation.
- Treat update-check failure as non-critical.

## 14. Testing strategy

### Unit tests

- URL recognition, normalization, redirect validation, and canonical deduplication.
- Author catalog, series, standalone story, chapter, continuation-page, unavailable-page, and changed-markup parsers.
- Content sanitizer allowlist/denylist and XHTML output.
- Series ordering, including decimal-looking labels such as `05.1` without relying on floating-point comparison.
- TOC invariants, reorder operations, and immutable export snapshots.
- Retry classification, backoff caps, cancellation, and partial failure behavior.
- Filename sanitization and collision naming.
- Cache retention and refresh reconciliation.
- EPUB package paths, IDs, metadata, manifest, spine, nav, NCX, and link rewriting.
- ZIP bomb/slip, malformed XML, missing OPF, broken manifest, and DRM detection.

### Integration tests

- Use MockWebServer and sanitized fixtures for author → series → chapters end-to-end resolution.
- Test throttling, Retry-After, timeouts, redirect rejection, partial outages, and cancellation.
- Generate combined and separate EPUBs, reopen them through the EPUB importer, and assert structural equivalence.
- Merge representative EPUB 2 and EPUB 3 fixtures containing nested TOCs, CSS, images, fonts, SVG, footnotes, and relative links.
- Exercise Room migrations and process-death job recovery.
- Exercise Storage Access Framework permission persistence and revoked-URI recovery with fakes/instrumentation.

### UI tests

- Share intent → analysis → selection → export.
- Multi-link paste with duplicates and invalid inputs.
- Author search, sort, Select all, series expansion, and chapter override.
- TOC reordering and imported locked subtree behavior.
- Missing-download Retry/Export without flow.
- Metadata and cover customization.
- Light, dark, large-font, TalkBack semantics, and reduced-motion behavior.
- Privacy lock, recent-app shielding, and neutral notification text.

### EPUB acceptance matrix

Open validation fixtures in a small compatibility set covering:

- Android EPUB readers with modern EPUB 3 navigation.
- An older reader that depends on NCX.
- Calibre desktop tooling.
- EPUBCheck in CI.

Do not claim broad compatibility until the nested TOC, cover, links, imported assets, and Unicode punctuation have been verified across this matrix.

### Performance tests

- Benchmark parsing and packaging independently from network time.
- Confirm a 100-chapter text-only cached collection builds without UI jank or excessive memory use.
- Stream ZIP input/output rather than loading entire books into memory.
- Confirm cancellation releases HTTP calls, workers, streams, and temporary files.
- Measure cold analysis, cached re-export, merge throughput, peak memory, APK size, and battery impact.
- Establish release thresholds from a representative Android 9-era device during the feasibility phase rather than inventing network-duration guarantees.

## 15. Implementation milestones

### Milestone 0 — Repository and feasibility spike

Deliverables:

- Scaffold the Android project, modules/packages, CI, formatting, static analysis, and unit-test conventions.
- Record supported sample URL shapes and sanitized parser fixtures.
- Prove direct on-device HTTP access to representative public author, series, and story pages.
- Prove catalog parsing, explicit series resolution, ordered chapter extraction, and clean XHTML conversion.
- Prove a minimal EPUB 3 + NCX file can be opened and validated.
- Decide the concrete EPUB implementation strategy after testing available libraries against import/merge requirements.

Exit criteria:

- A test harness resolves the supplied example author into standalone works and series using fixtures and an opt-in live smoke test.
- A representative multi-part series exports in correct order.
- The generated minimal EPUB passes EPUBCheck.
- Any blocking anti-bot, TLS, markup, or library constraint is documented before full UI work.

### Milestone 1 — Domain core and draft editor

Deliverables:

- Domain entities, collection invariants, canonical deduplication, and TOC operations.
- Create-flow state machine and immutable export snapshot.
- Unit tests for selection, reordering, duplicate merges, and standalone/series hierarchy.

Exit criteria:

- A fully populated collection can be constructed, edited, validated, and serialized without Android UI dependencies.

### Milestone 2 — Production Literotica adapter

Deliverables:

- URL classifier and host-safe HTTP client.
- Author, series, story, continuation, and content parsers.
- Retry/throttle policy, cancellation, diagnostics, and sanitized fixture suite.
- Exact duplicate elimination and series membership resolution.

Exit criteria:

- All supported input forms produce the same canonical domain model.
- Markup changes yield targeted parser errors rather than crashes or incorrect silent output.

### Milestone 3 — Generated EPUB pipeline

Deliverables:

- XHTML renderer, divider pages, stylesheets, cover generator, package writer, nav, and NCX.
- Combined and Separate Books modes for downloaded content.
- Fast structural validator and EPUBCheck golden tests.

Exit criteria:

- Standalone, series, multi-work, and multi-author fixtures pass validation and show the agreed nested TOC in the compatibility matrix.

### Milestone 4 — EPUB import and merging

Deliverables:

- Safe EPUB 2/3 reader and DRM/unsupported-feature reporting.
- Namespaced resource copier and reference rewriter.
- Imported TOC preservation and outer collection packaging.
- Exact/likely duplicate signals for imported books.

Exit criteria:

- Representative complex, DRM-free EPUBs merge without broken images, CSS, fonts, internal links, spine order, or nested navigation.
- Malicious/malformed archives fail safely within resource limits.

### Milestone 5 — Persistence, caching, and job execution

Deliverables:

- Room schema, repositories, migrations, DataStore preferences, and cache manager.
- WorkManager/foreground download-export coordinator with durable progress and recovery.
- Saved collections, refresh reconciliation, changed/new chapter flags, and import relinking.

Exit criteria:

- A long job survives activity recreation and process death.
- Saved collections re-export from cache offline.
- Partial failures always require an explicit user decision.

### Milestone 6 — Create experience

Deliverables:

- Share intent and multi-link input.
- Analysis progress/errors.
- Author catalog search/sort/selection.
- TOC editor, duplicate review, metadata editor, and cover preview.
- Export progress, failure resolution, and completion actions.

Exit criteria:

- A first-time user can complete every core path without visiting Settings or reading documentation.

### Milestone 7 — Library, Settings, and privacy

Deliverables:

- Collection library and export history.
- Manual source refresh and imported-file relinking.
- Theme, Dynamic Color, concurrency, style, folder, and cache settings.
- Privacy mode, secure recent-app behavior, neutral notifications, and one-time 18+ notice.
- Manual app update check.

Exit criteria:

- All agreed secondary workflows function and accessibility checks pass.

### Milestone 8 — Hardening and release candidate

Deliverables:

- Performance/memory profiling and streaming fixes.
- Full EPUB compatibility matrix and live parser smoke suite.
- Offline, throttled, interrupted, revoked-permission, low-storage, and malformed-input testing.
- Signed release build, reproducible build notes, privacy statement, personal-use notice, and release checklist.

Exit criteria:

- No critical/high defects.
- EPUB fixtures pass EPUBCheck.
- No silent content loss in fault-injection tests.
- Release APK behaves correctly on Android 9 and a current Android version.

## 16. Release acceptance criteria

The first release is complete only when all of the following are demonstrably true:

1. The supplied author URL loads a selectable catalog containing standalone works and explicitly grouped series.
2. Selecting any series includes every ordered part by default.
3. Sharing an individual part resolves the full series when the site identifies its membership.
4. Multiple links combine without duplicate works or chapters.
5. Downloaded EPUBs contain no Literotica story images, ads, navigation, ratings, comments, scripts, or tracking elements.
6. Combined output uses the approved Author → Work → Chapter hierarchy and clear divider pages.
7. Separate mode creates one valid EPUB per selected work.
8. A valid, DRM-free EPUB 2 or EPUB 3 can be imported and merged with its internal TOC and supported assets intact.
9. Generated and merged EPUBs pass structural validation and EPUBCheck.
10. Missing chapters are never silently omitted; the user must retry or explicitly approve an incomplete export.
11. Saved collections can be reopened, refreshed manually, edited, and re-exported.
12. Cached collections can be re-exported offline when all required downloaded content remains cached.
13. Output is saved through the system document APIs with no silent overwrite.
14. The UI works in light/dark themes, with large text and screen-reader semantics.
15. Privacy mode protects app entry, recent-app previews, and notification titles.
16. No telemetry or story-content network transmission occurs beyond fetching user-selected public source pages and explicit user sharing.

## 17. Principal risks and mitigations

### Site markup or anti-bot behavior changes

- Isolate the adapter, prefer semantic selectors, maintain sanitized fixtures, add an opt-in live smoke suite, produce useful diagnostics, and support manual app update checks.

### “Any valid DRM-free EPUB” is a broad compatibility promise

- Perform the importer/merger spike early, support EPUB 2 and 3 explicitly, preserve unknown safe resources, report scripted/unsupported features, use a diverse golden corpus, and run EPUBCheck continuously.

### Fast downloads may trigger throttling

- Use bounded/adaptive concurrency, honor server signals, retry with jitter, expose a lower concurrency setting, and optimize cached/local stages rather than making unsafe network-speed promises.

### Large imported books may exhaust memory or storage

- Stream ZIP entries, impose declared and actual size limits, preflight free space, write to temporary files, and clean up reliably on cancellation/failure.

### Storage permissions and moved files

- Use persisted document permissions, track availability, relink missing imports, and keep collection metadata even when a referenced document disappears.

### Copyright, terms, and sensitive content

- Restrict the app to user-initiated access to public pages, avoid bypass features, show a personal-use notice, omit redistribution features beyond Android's ordinary explicit Share action, and store content privately by default.

## 18. Recommended first execution slice

Start with one vertical tracer path before building the full architecture:

1. Scaffold a minimal Android app and pure parser/EPUB test modules.
2. Accept the supplied author URL in a developer-only screen.
3. Parse the catalog into normalized standalone and series domain objects.
4. Select one multi-part series.
5. Download and clean every part with two concurrent requests.
6. Generate a plain EPUB 3 + NCX with the correct nested TOC and no cover customization.
7. Validate it with EPUBCheck and open it in two readers.

This slice directly tests the riskiest chain—live access, HTML contracts, ordering, sanitization, EPUB construction, and reader compatibility—before persistence, polished Compose UI, EPUB merging, or privacy features expand the implementation surface.
