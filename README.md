# StoryWeaver

Local-first Android app that downloads public Literotica stories/series as EPUB files and can combine them with existing DRM-free EPUBs.

See [PLAN.md](PLAN.md) for the full product and technical plan.

## Status

**Status (v0.3)** — M0–M7 product features + remaining Create/Library/Settings paths:

- **Create:** multi-URL analyze, story→series resolution, catalog search/sort, series chapter overrides, work reorder, metadata, cover accent, optional filename, combined/separate export, DRM-free EPUB import + merge (combined only), retry/export-without failures, share / open in reader / save to output folder, save collections
- **Merge:** combine any number of DRM-free EPUBs into one book; reorder, set title/author, optional generated cover and per-book title pages. The merged table of contents follows the merge order, with each book's own (nested) TOC kept under its entry.
- **Library:** saved collections, open, manual refresh with new/changed/unavailable chapter signals, delete, recent export history
- **Settings:** theme, dynamic color, concurrency, EPUB style, default SAF output folder, privacy mode, cache clear, manual update check
- Local Room cache + DataStore preferences; EPUB cover, import/merge engine

**Still deferred / hardening (M8):** WorkManager process-death recovery for long jobs, full EPUBCheck CI matrix.

## Install

Download the APK from [Releases](https://github.com/amangujar21/LiEpub/releases/latest). Builds before v0.3.0 were debug-signed, so uninstall an older copy first (this clears its Library).

## Build

Requirements: JDK 17, Android SDK (compileSdk 35).

```bash
./gradlew test
./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

### Release build

```bash
./gradlew :app:assembleRelease
```

Release APK (R8-shrunk, ~4 MB): `app/build/outputs/apk/release/app-release.apk`.

Signing is read from `~/.storyweaver/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the env vars `SW_KEYSTORE_FILE`, `SW_KEYSTORE_PASSWORD`, `SW_KEY_ALIAS`, `SW_KEY_PASSWORD`. Without either, the release APK is unsigned. Keep a backup of the keystore: updates must be signed with the same key.

The in-app update check reads [`latest.json`](latest.json); bump it with each release.

## Modules

- `:app` — shell, theme, navigation, SAF helpers
- `:core:model` — domain
- `:core:network` — HTTP policy
- `:core:database` — Room collections, cache, export history
- `:source:literotica` — parsers
- `:epub` — EPUB writer, importer, merger, structural validator
- `:feature:create|merge|library|settings` — UI

## Notice

Personal-use tool. Not affiliated with Literotica. Only public pages; no login/DRM bypass. 18+ content responsibility stays with the user.
