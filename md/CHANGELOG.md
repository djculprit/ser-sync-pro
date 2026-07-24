# Changelog — cdd-sync-pro

All notable changes to this project will be documented in this file.

---

## [Unreleased]

- **Fix: malformed `osrt` block broke Steps 3/4 under Serato DJ Pro 4**: Every crate this tool ever created from scratch (no pre-existing `.crate` file to preserve headers from) wrote its `brev` (sort-direction) sub-tag as a 5-byte integer with `DEFAULT_SORTING_REV = 1 << 8` (256). A real Serato-written crate encodes `brev` as a single byte (confirmed byte-for-byte against `Subcrates/*.crate` exported by Serato itself). Legacy Serato silently tolerated the malformed tag and still read the crate's track list; Serato DJ Pro 4's stricter import validator aborts parsing the *entire* crate the instant it hits the bad tag (confirmed via Serato's own diagnostic log: `Malformed DBv2 tag 'brev' found at position 88` / `Import will continue but the remainder of the crate will be skipped`). This is why brand-new crates created by Step 4 (and tracks appended by Step 3 to a crate that had never been re-exported by Serato) appeared to do nothing under Serato 4 — not because Serato 4 stopped reading `.crate` files, but because every such file was silently rejected at import. This bug predates the Python port — the original Java implementation (`java/cdd-sync-pro/src/cdd_sync_crate.java`) has the identical `1 << 8`/5-byte encoding.
  - `python/core/serato_parser.py`: `DEFAULT_SORTING_REV` changed from `1 << 8` to `1`; `_build_osrt_payload()` now writes `brev` as a 1-byte value with a length-1 tag instead of 5 bytes. Verified against a real Serato-exported crate's `osrt` bytes, and against Serato's own log output after re-running `create_new_crates()` on a real test folder (crate imported cleanly, all tracks visible, no malformed-tag warning).

- **Feat: Dupe Manager gets its own Scan/Run buttons (Step 0)**: Duplicate Management now runs standalone, same as Steps 1-4. `run_step0()` added to `sync/pipeline.py`, honoring `config.dry_run` for preview-vs-execute like the other step runners.
  - When run standalone and duplicates are actually moved, `run_step0()` now also rescans the library and re-points any `.crate` files that referenced the moved-away files (previously only `database V2` got patched; crates stayed broken until a separate Step 2 run — only masked in a full `run_sync()` because Steps 1-2 always followed Step 0 there).
  - `sync/dupe_mover.py`: extracted `group_by_key()` / `group_duplicates()` / `resolve_keep_and_move()` / `preview_duplicate_groups()` so the dry-run preview and the real mover share one grouping implementation instead of duplicating it. `scan_and_move_duplicates()` now accepts `log_callback` and `crate_index` and logs every kept/moved file individually (previously logged only via the file logger, invisible in the GUI).
  - Added `build_crate_membership_index()` (`sync/pipeline.py`) — maps filename → crate names referencing it — and `describe_dupe_path()` (`sync/dupe_mover.py`) so every dupe log line shows the file's relative path *and* which crate(s) it belongs to.
  - Renamed "Duplicate Management" → "Dupe Manager" in the GUI; wrapped its controls in the same elevated card style used by Pipeline Steps / Session Fixer rows for visual consistency.

- **Feat: Step 4 no longer creates empty crates**: Folders with no direct tracks (only nested subfolders) were getting an empty `.crate` file created for the container folder itself. `create_new_crates()` and `_dry_run_step4()` now skip folders with zero direct tracks; summary line reports the skip count (e.g. `137 already exist, 3 empty folder(s) skipped`).

- **Feat: Per-track line-by-line pipeline logging**: Steps 1-4 (both dry-run preview and live run) now log a header line followed by one indented `• track.mp3` line per affected track, instead of a single long comma-joined string. Capped at 200 lines per crate/step (`… +N more (truncated)`) to avoid flooding the log on very large folders. New `_log_track_lines()` helper in `sync/pipeline.py`.

- **GUI: Log Output moved to its own tab**: Pipeline Steps, Dupe Manager, and Session Fixer now live under a "Pipeline" tab; the log panel gets a dedicated "Log" tab with the full available height instead of a fixed 180px strip. Uses Flet's `Tabs`/`TabBar`/`TabBarView` (`python/gui.py`).
  - The tab area now expands to fill available window height (`expand=True`) instead of a fixed height — resizing the window taller grows the Pipeline/Log view instead of leaving blank space below the bottom action bar.

---

## [2.0] — Python reimplementation (2026-04)

- **Feat: History Session Fixer ported to Python GUI**: New `sync/session_fixer.py` module ports the Java `session_fixer` tool. Scans `~/Music/_Serato_/History/Sessions/*.session` binary files for broken track paths and rewrites them using the Music Folder as the lookup source. Scan (dry-run) and Run (live) are exposed via dedicated buttons in the GUI — no checkbox required to run.
  - `python/sync/session_fixer.py`: New module. Uses `MediaLibrary.read_from()` + parallel `os.scandir` (same strategy as pipeline Step 2) to build a `lowercase_filename → [abs_path]` index upfront. Session files store absolute paths so no database normalisation is needed — `candidates[0]` is used directly. Ambiguous filename matches are skipped (consistent with Step 2). Live fix uses `ThreadPoolExecutor(max_workers=4)` for parallel session rewrites. Atomic write via `.tmp` rename.
  - `python/sync/session_fixer.py`: `scan_broken_paths()` writes a full `session_scan_report.txt` to `~/Music/_Serato_/` after every scan, categorising unfixable paths into **EXISTS BUT OUTSIDE MUSIC FOLDER** vs **NOT FOUND ANYWHERE** for easy diagnosis.
  - `python/gui.py`: Session Fixer section wired up with `_run_session_scan()` and `_run_session_fix()` handlers on daemon threads.

- **Fix: GUI log panel thread-safety (Flet IndexError crash)**: Concurrent `page.run_thread()` calls from rapid log output were causing Flet's `_compare_lists` diff to hit `IndexError: list index out of range`. Fixed by replacing per-message `page.run_thread(_do)` with a queue-based flusher: `_append_log()` enqueues `(msg, color)` tuples into a `queue.Queue`; a single 100ms daemon loop drains the entire queue and calls `page.update()` once per batch.
  - `python/gui.py`: `_log_queue`, `_append_log()`, `_start_log_flusher()`, `_clear_log()` updated.

- **Fix: Flet API compatibility (`page.open` / `page.snack_bar` / `_err` closures)**: Three API issues fixed across the GUI for Flet v0.84.0.
  - `page.open(ft.SnackBar(...))` — removed in this Flet version. All 15 instances replaced with `_append_log(f"⚠️ ...")` — no external API needed.
  - `page.snack_bar` — also absent. Removed along with the above.
  - `NameError: cannot access free variable 'exc'` — Python 3 deletes the `exc` binding after an `except` block exits. All 7 `_err` closures now use `def _err(_exc=exc)` to capture the value at definition time.
  - `python/gui.py`: All instances patched.

- **Fix: Step 4 "Create New Crates" now logs each crate being created**: Dry-run output now lists every crate that would be created (with track count) instead of only a total count. Live run emits a `Creating '...' (N track(s))...` line before writing and a `✓ Created` / `✗ Failed` result line after — failures now also appear in the log panel (previously silent).
  - `python/sync/pipeline.py`: `create_new_crates()` and `_dry_run_step4()` updated.

- **Style: Python GUI dark-mode polish pass**: Upgraded the Flet GUI from flat grey to a GitHub-dark navy palette, added a branded header with version badge, colorized log output, and a dedicated Dry Run warning pill.
  - `python/gui.py`: New color tokens (`#0f1117` bg, `#161b22` surface, `#21262d` border, `#58a6ff` blue, `#3fb950` green, `#d29922` amber, `#f85149` red, `#0a0c10` terminal bg). `_section()` gains `accent_color` + `header_action` params for 3px left-accent borders. Branded header row (app name, v2.0 badge, `● Live` pill). Pipeline Steps and Paths sections gain blue left-accent; Duplicate Management amber; Log panel purple. Dry Run moved to its own amber pill row below Pipeline Steps. Log panel uses terminal-dark bg with a trash Clear button in the header. Log lines colorized by prefix (✅ green / ❌ red / ⚠️ amber / 🔍 blue). Progress bar always visible at 4px height. Button radius bumped to 8. Section spacing 12→16, page padding 20.

  - Repacked all legacy Java silos, tests, and configuration into a logically separated `java/` directory as a readonly reference.
  - Initialized an empty `python/` structural directory target for the pending Builder agent.
  - Generated the primary Python Migration Action Plan resolving structural CLI vs GUI transitions (`md/actions/python-convert.md`).

- **Fix: Step 2 crate path fixer now uses Serato's exact filename encoding**: `fixExistingCrates()` previously resolved new track paths purely from the filesystem (`normalizePathForDatabase()`) and never consulted the Serato database. When the filesystem returned NFC-encoded paths and Serato had stored the filename in NFD, the written crate path diverged from `database V2`, causing Serato to silently create an orphaned duplicate record on next open. Fix: Step 2 now calls `resolveSeratoPath()` (already used by Steps 3 & 4 via `addTrack()`) before normalizing, so the database-stored encoding is preferred when available. Falls back to the filesystem path when the database is null or has no entry — no behaviour change for tracks not in the database.
  - `cdd_sync_crate_fixer.java`: `fixExistingCrates()` gains a `database` parameter; path-fix loop applies `resolveSeratoPath()` before `normalizePathForDatabase()`; `fixBrokenPaths()` facade threads `database` through.
  - `cdd_sync_main.java`: Step 2 call site updated to pass the already-loaded `database` variable.

- **Fix: Duplicate track insertion for accented filenames (NFC/NFD)**: Tracks with special characters (e.g. `Bota Niña`) were being inserted twice into crates — once from the existing crate (NFC) and once from the filesystem scan (NFD). `addTrack()` now deduplicates by **filename leaf only** (NFC-normalized, lowercased), making the key immune to relative vs. absolute path differences between crate binary paths and filesystem paths. `setTracksRaw()` rebuilds the set so Step 3 correctly sees all paths already present after a Step 2 rewrite.
  - `cdd_sync_crate.java`: Extracted `normalizeForDedup()` helper (filename-only NFC+lowercase); `addTrack()` uses it for O(1) dedup key; `setTracksRaw()` rebuilds set using same key.

- **Removed: Fix Paths button**: Amber "Fix Paths" button removed from the GUI. Its functionality (Steps 1+2 only) is fully covered by toggling Steps 3 and 4 off in the Pipeline Steps panel before clicking Start. Removes ~75 lines of duplicated setup logic.
  - `cdd_sync_pro_window.java`: `fixPathsButton`, `onFixPathsCallback`, `onFixPathsClicked()`, `setOnFixPathsCallback()` all removed.
  - `cdd_sync_main.java`: `runFixPaths()` method and wiring block removed.

- **Style: Pipeline Steps and Duplicate Management label expansion**: All abbreviated GUI labels expanded to full descriptive names for clarity.
  - Step 0: "Duplicate mgmt" → "Duplicate Management"
  - Step 1: "Fix DB paths" → "Fix Database Paths"
  - Step 2: "Fix crate paths" → "Fix Existing Crate Paths"
  - Step 3: "Append tracks" → "Append Existing Crates"
  - Step 4: "Create crates" → "Create New Crates"
  - Post: "Sort crates A→Z" → "Reset Crates: A-Z"

- **Fix: Serato crate column widths preserved on round-trip rewrite (Step 2)**: Crates rewritten by Step 2 (path fixer) no longer show as blank in Serato. Root cause: `writeTo()` hardcoded `tvcw = "0"` for all columns, destroying the pixel widths Serato stores per-crate. Fix: `readFrom()` now captures the raw `ovct` and `osrt` TLV payloads as byte arrays; `writeTo()` emits them verbatim when present, bypassing reconstruction. New crates (Step 4) are unaffected — raw payloads absent → existing default logic runs unchanged.
  - `cdd_sync_crate.java`: Added `rawOsrtPayload` / `rawOvctPayloads` fields; `writeTo()` branches on their presence.

- **Refactor: `cdd_sync_crate.readFrom()` → unified TLV walker**: Replaced the two-loop, `mark/reset`-peek implementation with a single `while` loop that reads each top-level block into a `byte[] payload` before dispatch. Eliminates stream slippage on variable-length `ovct`/`osrt` blocks (the original cause of certain crates — e.g. `Current%%Base%%2026` — being parsed with 0 tracks). Three private payload-only helpers `extractPtrk`, `extractTvcn`, `extractOsrt` + a shared `walkPayloadForTag` walker replace the bespoke per-tag byte arithmetic.
  - `cdd_sync_crate.java`: `readFrom()` rewritten; four private helpers added; `writeTo()` / public API unchanged.

- **Fix: Step 2 crate write now uses in-place mutation**: `fixExistingCrates()` previously built a scratch `fixedCrate` object and manually copied version/sorting/columns, risking silent field mismatches. Now mutates the already-read `crate` directly via `setTracksRaw()` and calls `writeTo()` on it — identical pattern to Steps 3 and 4.
  - `cdd_sync_crate_fixer.java`: Scratch-copy pattern removed.

- **Per-step pipeline debug toggles**: Each of the five sync pipeline steps (Step 0–4) can now be independently enabled or disabled from the **Pipeline Steps** panel in the GUI. All previous sync option controls (Backup, Clear Library, Sort Crates) have been merged into this single panel, displayed in execution order (Pre-1 → Pre-2 → Step 0 → Step 1 → Step 2 → Step 3 → Step 4 → Post). Toggles are also exposed as `sync.step0.enabled`–`sync.step4.enabled` properties for CLI/config-file use. Allows any single step to be isolated without running the full pipeline.

- **Fix: Step 2 now processes ALL crates including hand-curated Live sets**: Replaced the multi-threaded, ambiguous-lookup Step 2 implementation with a simple sequential loop. All `.crate` files in `Subcrates/` are now processed regardless of whether they map to a filesystem folder. The database V2 (already patched by Step 1) is the sole source of truth — if a track's filename resolves to a different path in the DB, the crate is updated. Previously, custom crates were silently skipped due to a flawed directory-mapping gate.
  - `cdd_sync_crate_fixer.java`: Removed `ExecutorService`, `ConcurrentHashMap`, and multi-value ambiguity logic. Replaced with flat `Map<String, String>` index and single `for` loop.
  - `cdd_sync_main.java`: Added explicit log messages when Step 1 or Step 2 are skipped so the pipeline is never silently bypassed.
  - `cdd_sync_crate.java`: Removed unused `listCrateFiles(File)` helper.

- **4-step sync pipeline**: `runSync()` now executes four discrete, non-overlapping steps instead of the old monolithic `cdd_sync_library.writeTo()` approach. Step 1 fixes broken paths in database V2; Step 2 repairs broken paths in existing hand-curated crates (via `setTracksRaw()` — no dedup, no removal); Step 3 appends new tracks to existing folder-mapped crates; Step 4 creates new crates only for directories with no matching crate file on disk. Existing crates are never overwritten.
- **Fix Paths standalone mode**: New amber **Fix Paths** button in the GUI runs `runFixPaths()` — scans and repairs broken paths in existing crates and database V2 without writing any new crates.
- **Per-step diagnostic log files**: Each sync session now creates seven timestamped log files: main log, dupe log, path-fix log, and four step-level logs (`step1-db-fix`, `step2-crate-fix`, `step3-append`, `step4-create`). Verbose per-path detail never floods the GUI; it goes to file only.
- **GUI tooltip documentation**: All sync options and duplicate management controls now show detailed `[DEBUG]`-tagged tooltips explaining exact behavior, config key, and interaction effects.
- **Clear-library guard**: Enabling **Clear library before sync** now requires confirmation via a warning dialog before the checkbox can be checked.

- **CI pipeline**: GitHub Actions workflow (`.github/workflows/build.yml`) now runs `ant test` on every push and pull request targeting `master`. Build status badge added to `README.md`.
- **`--dry-run` CLI flag**: Pass `--dry-run` as a command-line argument (CLI mode only) to preview a full sync without writing anything to disk. All 7 write sites (backup, dupe mover, database fixer, Serato folder creation, parent crate creation, crate library write, broken path fixer, crate sorter) log `[DRY RUN] Would have: ...` instead of executing. Exits with `[DRY RUN] Sync complete — no files were written.`. GUI mode is unaffected.
- **Codebase cleanup**: Consolidated 4 duplicated path normalization methods into `ser_sync_binary_utils.normalizePath()` and `normalizePathForDatabase()`. Fixed stream leaks in config constructors (try-with-resources). Converted `ser_sync_dupe_mover` from static mutable state to instance-based (fixes re-entrant GUI bug). Replaced 17 regex `Pattern` objects in `ser_sync_media_library.isMedia()` with a `Set<String>` lookup. Indexed otrk blocks in `ser_sync_database_fixer` to eliminate O(N²) scanning. Removed verbose per-group dupe logging from GUI output. Removed unnecessary `System.exit(0)` from session-fixer. Fixed test compilation target (1.8 → 11). Added path normalization and crate round-trip tests (26 total).
- **Silo restructure**: Moved source files into `shared/src/` (9 shared), `cdd-sync-pro/src/` (11 app-only), and `session-fixer/src/` (4, unchanged). Updated `build.xml` to compile from all three directories.
- **Bug fix**: Smart crate write always rewrote all 138 crates due to `Collections.unmodifiableCollection()` not overriding `equals()`. Changed `getColumns()` to return `unmodifiableList()` for proper element comparison.
- **Logs to volume**: Log files now write to `<volume>/cdd-sync-pro/logs/` alongside backup and dupes instead of CWD-relative `logs/`.
- **GUI config window**: New interactive dark-themed config panel (`ser_sync_pro_window.java`) with path fields + Browse buttons, sync option checkboxes, duplicate management dropdowns, log output area, and Start/Cancel buttons. Runs sync on SwingWorker background thread. Settings persist to `ser-sync.properties`. Session-fixer unaffected (Option A architecture).

## Past Highlights (from Git history)

- **dc8b11a** — Create `dependabot.yml` for automated dependency updates
- **0a8ee2f** — Add detailed guide for the Serato `.session` file format
- **cc94a8a** — Introduce batch synchronization mode with config file support and server-side rename detection
- **b69bfe7** — Allow date-based selection of Serato database entries when fixing duplicate broken paths in crates
- **982ac64** — Refactor: Move session-fixer to standalone silo
