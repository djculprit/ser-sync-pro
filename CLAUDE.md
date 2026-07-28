# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

cdd-sync-pro is a Serato DJ crate synchronization tool: it mirrors filesystem directory
structures to Serato `.crate` files, fixes broken track paths in crates/database/session
files, deduplicates tracks, and backs up the `_Serato_` folder before writing.

The repo contains two implementations of the same tool:

- **`python/`** — PRIMARY, actively maintained. Flet-based GUI + headless CLI, Python 3.12+.
- **`archive/java/`** — read-only reference implementation (original). Do not modify unless
  explicitly asked; treat it as historical/reference only.
- **`s3-smart-sync/`** — separate, unrelated companion tool (S3 sync), its own Python silo.

When asked to fix bugs or add features to "the app" with no further qualification, assume
`python/` — it is the one users run.

## Commands

### Python app (`python/`)

```bash
cd python
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt -r requirements-dev.txt

python main.py                  # Launch GUI (default)
python main.py --cli            # Headless CLI, requires config.yaml (copy from config.template.yaml)
python main.py --cli --dry-run  # Preview sync, writes nothing to disk

pytest                          # Run all tests (from python/, uses pyproject.toml testpaths)
pytest tests/test_path_utils.py -v          # Single file
pytest tests/test_path_utils.py::test_name  # Single test
```

Double-clicking `run.command` (repo root) sets up the venv if needed and launches the GUI.

### Java reference (`archive/java/`)

```bash
cd archive/java
ant all      # Clean + build cdd-sync-pro and session-fixer
ant test     # Run JUnit 5 tests
ant run      # Build and run cdd-sync-pro
ant session-fixer-run
```
CI (`.github/workflows/build.yml`) runs on push/PR to `main`: `java-test` (`ant test` against
`archive/java/`) and `python-test` (`pytest` against `python/`).

## Architecture (`python/`)

Entry point `main.py` launches the Flet GUI by default, or runs headless via `--cli`
(reads `config.yaml`, built from `config.template.yaml`; `SyncConfig` in `config.py`).

- **`core/`** — low-level, stateless parsing/formatting:
  - `binary_utils.py` — big-endian I/O helpers.
  - `path_utils.py` — Unicode NFC/NFD normalization, volume-prefix stripping, dedupe keys.
    Critical: Serato's `database V2` stores paths in **NFD**; macOS filesystem APIs
    typically return **NFC**. All path comparisons must normalize consistently or broken-path
    detection and dedup silently fail. See "Path format requirements" below.
  - `serato_parser.py` — `Crate` and `SeratoDatabase` TLV (tag-length-value) read/write,
    byte-for-byte round-trip.
  - `session_log.py` — `session_log_file(name, serato_path)` context manager: attaches a
    `FileHandler` to the shared `cdd_sync` logger for one run, writing a timestamped log to
    `<parent of _Serato_>/cdd-sync-pro/logs/`. See "Session logs" below.
- **`sync/`** — the pipeline, orchestrated by `pipeline.py:run_sync()`:
  1. `backup.py` — timestamped backup of `_Serato_`.
  2. `media_library.py` — parallel (ThreadPoolExecutor) recursive scan of the music folder.
  3. `dupe_mover.py` — optional: detects/moves duplicate files on disk, triggers a library rescan.
  4. Crate-fixing steps (mirrors the four-step Java `cdd_sync_crate_fixer` design):
     Step 1 fixes broken paths in `database V2` first (so it's authoritative), Step 2 fixes
     broken paths in existing `.crate` files, Step 3 appends new tracks to crates that already
     map to a folder, Step 4 creates new crates for folders with none yet. Steps 3 and 4 are
     mutually exclusive per crate — an existing crate file is never overwritten by Step 4.
  5. `database_fixer.py` — binary `database V2` TLV path patcher.
  6. `pref_sorter.py` — regenerates `neworder.pref` (UTF-16BE, atomic write) for alphabetical
     crate ordering.
  7. `session_fixer.py` — separate flow (not part of `run_sync`): repairs broken paths in
     Serato `.session` history files; ported from the Java `session-fixer` tool.

`gui/` is a Flet dark-mode front end over the same `sync.*` modules — `__init__.py` holds the
app shell and handlers, `theme.py` holds stateless colors/widget builders, `config_io.py` holds
the config bundle/build/load bridge. Treat the package as a thin wrapper, not where sync logic
should live.

### Path format requirements (must-know when touching path logic)

| Component | Required format |
|---|---|
| `.crate` files | Relative path, NFD encoded: `Crates/Folder/file.mp3` |
| `database V2` `pfil` | Relative path, NFD encoded |
| Filesystem lookups | Absolute path, NFC encoded |

Getting normalization wrong here reintroduces the exact bug class this codebase was
written to fix — orphaned/duplicate Serato database entries. Prefer the existing
`normalize`/comparison helpers in `core/path_utils.py` over ad hoc `unicodedata` calls.

### Smart writes

Both crate writing (Python) and its Java ancestor read the existing file on disk first and
skip the write if content is unchanged (after normalizing paths for comparison) — this
avoids redundant disk I/O and log noise. Preserve this behavior when modifying write paths.

### Session logs

Every write-capable entry point (`run_sync()`, `session_fixer.scan_broken_paths()`,
`session_fixer.fix_broken_paths()`) opens a timestamped log file via
`core/session_log.py:session_log_file()`. Logs land at
`<parent of _Serato_>/cdd-sync-pro/logs/`, matching the original Java tool
(`cdd_sync_main.java`'s `setLogDirectory()`) — this is deliberate: Serato libraries typically
live on external volumes, so logs travel with the drive instead of collecting under wherever
the app happens to be launched from. Never hardcode a CWD-relative `logs/` path; derive it
from `config.serato_library_path` (or the `serato_path` argument) via `session_log_file`.

## Reference docs

- `README.md` — user-facing feature list and config reference.
- `md/CODEBASE_GUIDE.md` — detailed module-by-module reference, including the original
  Java architecture the Python code was ported from, Serato binary format notes, and known
  limitations (NFC/NFD, Serato's own duplicate-creation behavior on relocated files).
- `md/CONCEPTS.md` — glossary of Serato-specific terms (crate, TLV, `pfil`/`otrk`/`oent`, etc.).
- `md/CHANGELOG.md`, `md/TODO.md` — project history and open items.
- `md/actions/` — phased action plans/audit trails for past work.
