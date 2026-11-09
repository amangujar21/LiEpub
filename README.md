# StoryWeaver

Local-first Android app that downloads public Literotica stories/series as EPUB files and can combine them with existing DRM-free EPUBs.

See [PLAN.md](PLAN.md) for the full product and technical plan.

## Status

**Status (v0.3)** — M0–M7 product features + remaining Create/Library/Settings paths:

- **Create:** multi-URL analyze, story→series resolution, catalog search/sort, series chapter overrides, work reorder, metadata, cover accent, optional filename, combined/separate export, DRM-free EPUB import + merge (combined only), retry/export-without failures, share / open in reader / save to output folder, save collections
- **Library:** saved collections, open, manual refresh with new/changed/unavailable chapter signals, delete, recent export history
- **Settings:** theme, dynamic color, concurrency, EPUB style, default SAF output folder, privacy mode, cache clear, manual update check
- Local Room cache + DataStore preferences; EPUB cover, import/merge engine

**Still deferred / hardening (M8):** WorkManager process-death recovery for long jobs, full EPUBCheck CI matrix, signed release packaging notes.

## Build

Requirements: JDK 17, Android SDK (compileSdk 35).

```bash
./gradlew test
./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Modules

- `:app` — shell, theme, navigation, SAF helpers
- `:core:model` — domain
- `:core:network` — HTTP policy
- `:core:database` — Room collections, cache, export history
- `:source:literotica` — parsers
- `:epub` — EPUB writer, importer, merger, structural validator
- `:feature:create|library|settings` — UI

## Notice

Personal-use tool. Not affiliated with Literotica. Only public pages; no login/DRM bypass. 18+ content responsibility stays with the user.
