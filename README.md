# cdd-sync-pro

![Build](https://github.com/cyber-demon-dev/cdd-sync-pro/actions/workflows/build.yml/badge.svg)

Serato DJ crate synchronization tool — automatically sync your filesystem folders to Serato crates and more.

## License

[GNU GPL v3](http://www.gnu.org/licenses/gpl.html)

Based on [serato-sync](https://github.com/ralekseenkov/serato-sync-old/) by Roman Alekseenkov.

## Features

- **Folder → Crate Mapping**: Mirror your directory structure directly to Serato crates
- **Smart Crate Writing**: Only updates `.crate` files if content has changed, preserving disk I/O
- **Robust Path Normalization**: Intelligently handles Unicode (NFC/NFD) and absolute/relative path differences
- **Smart Deduplication**: Prevents duplicate tracks using Unicode-aware filename matching
- **Pre-Sync Backup**: Automatically backs up `_Serato_` folder with preserved timestamps
- **Parent Crate Support**: Add synced folders as subcrates under existing Serato crates
- **Alphabetical Crate Sorting**: Automatically sort crates A–Z in Serato via `neworder.pref`
- **Duplicate File Scanner**: Logs duplicate files on disk (log-only, no move)
- **Duplicate File Mover**: Moves duplicate files aside (keep newest or oldest)
- **Broken Filepath Fixer**: Automatically repairs broken track paths in existing crates and database V2
- **Session Fixer**: Standalone tool to fix broken paths in Serato `.session` history files
- **GUI Config Window**: Dark-themed Flet interface with per-step scan/run, config editing, and live log output
- **Headless CLI**: Run the full pipeline from `config.yaml`, no GUI required
- **Dry-Run Mode**: Preview a full sync without writing anything to disk (`--dry-run`)
- **Volume-Relative Session Logs**: Every sync/session-fixer run writes a timestamped log to `<parent of _Serato_>/cdd-sync-pro/logs/`, alongside the backup — logs travel with the drive, not the app

## Quick Start

Requires **Python 3.12+**.

```bash
cd python
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt

python main.py                  # Launch GUI (default)
```

Or double-click `run.command` in the repo root — it sets up the venv if needed and launches the GUI.

### Headless CLI

```bash
cd python
cp config.template.yaml config.yaml   # then edit paths/options
python main.py --cli                  # Run the sync pipeline
python main.py --cli --dry-run        # Preview only, writes nothing to disk
```

## Configuration Options (`config.yaml`)

| Option | Description | Default |
| ------ | ----------- | ------- |
| `music_library_path` | Absolute path to your music library root | Required |
| `serato_library_path` | Absolute path to your `_Serato_` folder | Required |
| `parent_crate_path` | Optional top-level parent crate name | None |
| `clear_library_before_sync` | Clear existing crates before syncing | `false` |
| `backup_enabled` | Timestamped backup of `_Serato_` before syncing | `true` |
| `dupe_scan_enabled` | Log duplicate files on disk (no move) | `false` |
| `dupe_move_mode` | `false`, `keep-newest`, or `keep-oldest` | `false` |
| `dupe_detection_mode` | `off`, `name-only`, or `name-and-size` | `off` |
| `crate_sorting_enabled` | Sort crates A–Z via `neworder.pref` | `false` |
| `step0_enabled` … `step4_enabled` | Enable/disable individual pipeline steps | `true` |
| `dry_run` | Preview sync — writes nothing to disk | `false` |

See `python/config.template.yaml` for the full annotated template.

## Testing

```bash
cd python
pytest                                       # Run all tests
pytest tests/test_path_utils.py -v           # Single file
pytest tests/test_path_utils.py::test_name   # Single test
```

## Project Structure

```text
cdd-sync-pro/
├── python/                             # PRIMARY — Flet GUI + headless CLI (Python 3.12+)
│   ├── main.py                         # Entry point — GUI (default) or --cli [--dry-run]
│   ├── gui.py                          # Flet dark-mode front end
│   ├── config.py                       # SyncConfig — YAML load/save
│   ├── core/                           # Stateless parsing/formatting (binary I/O, path utils, TLV parser)
│   ├── sync/                           # Pipeline: backup, scan, dedupe, crate/database fixers, pref sorter, session fixer
│   └── tests/                          # pytest suite
├── archive/java/                       # Archived — original Java implementation (read-only reference)
├── s3-smart-sync/                      # S3 sync companion tool (Python, separate silo)
├── md/                                 # Internal docs (CODEBASE_GUIDE, CONCEPTS, CHANGELOG, TODO, etc.)
└── README.md
```

## How It Works

1. **Backup**: Creates timestamped backup alongside your `_Serato_` folder
2. **Scan**: Reads your music library directory structure
3. **Fix Paths** (optional): Repairs broken filepaths in existing crates and updates database V2
4. **Deduplicate**: Skips tracks already in Serato's database
5. **Build Crates**: Creates `.crate` files mirroring your folder structure (updates only if changed)
6. **Sort** (optional): Generates `neworder.pref` for alphabetical crate ordering

## Java Reference Implementation

`archive/java/` contains the original Java implementation this tool was ported from. It's kept
for historical reference only and is not actively maintained — see `CLAUDE.md` and
`md/CODEBASE_GUIDE.md` for details on building/running it if needed.
