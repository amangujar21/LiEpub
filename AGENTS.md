# StoryWeaver — agent rules

Spec: `PLAN.md`. When anything conflicts, product boundaries in PLAN.md §1 win.

## Product constraints

- Local-first Android app: fetch public Literotica pages, generate/merge DRM-free EPUBs on device.
- No backend, accounts, ads, analytics, or remote content processing.
- No Literotica login, CAPTCHA bypass, DRM removal, or regional restriction bypass.
- No built-in EPUB reader (open externally).
- minSdk 28 (Android 9+).

## Architecture

| Module | Role |
|--------|------|
| `:app` | Composition, navigation, theme, share intent, DI |
| `:core:model` | Pure domain types (no Android) |
| `:core:network` | OkHttp policy, host allowlist, retry |
| `:core:database` | Room (M5+) |
| `:source:literotica` | URL classifier, parsers, sanitizer |
| `:epub` | Package write, import/merge (import M4+), Markdown export |
| `:feature:create` / `merge` / `markdown` / `library` / `settings` | Compose UI |

**Layering:** UI → ViewModels/use cases → repositories → Literotica | Room | EPUB | SAF.

UI must not parse HTML, open ZIPs, or call HTTP directly.

## Build & test

```text
./gradlew test
./gradlew :app:assembleDebug
```

- Parser/EPUB/domain: JVM unit tests with fixtures under `source/literotica/src/test/resources/fixtures`.
- Do not commit full copyrighted story bodies; keep fixtures minimal and sanitized.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Subagent protocol

1. One owner per path; contract-first domain types.
2. Prefer sequential milestones (M0–M8) with green tests before the next.
3. Narrow prompts: file allowlist + PLAN.md section refs.

## Milestones

See PLAN.md §15. Milestones M0–M7 product features implemented (domain, adapter, EPUB write/import/merge UI, Room/cache, Create/Library/Settings, privacy, SAF folder, refresh reconciliation). M8 hardening ongoing (WorkManager process-death, EPUBCheck CI, release packaging).
