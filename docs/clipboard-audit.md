# NeoKey clipboard: phase 1 audit (2026-11-01, v19.8)

## Stack (verified from source)
- Java only, no Kotlin, no Gradle; manual build (javac + aapt2 + d8/r8). No Room, no AndroidX, no Material lib.
- minSdk 26, targetSdk 35. Keyboard = InputMethodService (NeoKeyService) + one custom-drawn View (KeyboardView). No XML layouts in the IME.
- Settings app = SetupActivity (programmatic views).
- No test infrastructure (Robolectric attempt failed earlier). Plain-JVM tests possible for pure-Java logic.

## Current clipboard
- Capture: ClipboardManager.OnPrimaryClipChangedListener in NeoKeyService + captureClip() on onStartInputView.
- Storage: SharedPreferences key "clips" = all texts joined by a SEP char; "pins" = set of pinned texts (keyed by text, not id).
- Limits: text truncated to 2000 chars (`s.substring(0, 2000)`), SEP chars in text replaced by space (silent mutation), 30 unpinned items, 6 pins.
- UI: PANEL_CLIP in KeyboardView: 2x4 grid, paging (id 77), "مسح الكل", long-press = pin toggle. No search, edit, menu, titles, categories.
- Incognito skips saving. Password fields set `sensitive` but captureClip does NOT check it; ClipDescription EXTRA_IS_SENSITIVE (Android 13+) not checked.
- Backup (BackupActivity) excludes clips/pins (good for privacy).

## Problems found
1. Long text silently cut at 2000 chars.
2. SEP character inside copied text is altered.
3. Whole history rewritten to prefs on every copy; prefs loaded fully into memory at startup.
4. Pins keyed by text: editing breaks pins; duplicates impossible to distinguish.
5. "مسح الكل" clears clips list so pinned items disappear too (pins set keeps orphan texts).
6. Sensitive copies (passwords/OTP flagged by source app) are saved to permanent history.
7. No item ids, timestamps, or schema version; no migration path.

## Android limits
- On Android 10+ only the focused app or the default IME can read the clipboard; NeoKey as default IME gets changes while it's the active keyboard. Copies made while another keyboard is active are not seen. No accessibility-service workaround will be used.
- Android 13+ shows a system "copied" toast; sources can mark clips sensitive (EXTRA_IS_SENSITIVE).

## Decision
- Storage: platform SQLite (android.database.sqlite, SQLiteOpenHelper) — no dependency, works with manual build. Room is not used because there is no annotation-processing build. Migration v0 (prefs) → DB v1 on first run, inside a transaction, prefs kept until verified.
- Long text: stored as TEXT; list queries read `substr(text,1,300)` + length only; full text loaded on demand. Save verified by read-back compare.
- INTERNET permission exists for translate/AI writer only; clipboard code will never touch the network or logs.

## Files to change/add
- new: ClipStore.java (SQLite, schema, migration, queries), ClipItem.java, ClipIO.java (TXT/JSON/CSV export, JSON import validation), ClipTools.java (text tools), ClipActivity.java (full library, editor, import/export via SAF).
- NeoKeyService.java (capture → store, sensitive check), KeyboardView.java (clip panel: tabs, search, cards, action menu), SetupActivity.java (settings entry), AndroidManifest.xml (activity).
- tests/: plain-JVM tests for ClipTools, ClipIO (JSON/CSV round-trip, 100k chars, Arabic/emoji).
