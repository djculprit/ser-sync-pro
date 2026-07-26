"""
Sync pipeline orchestrator — ports Java's cdd_sync_main.runSync() and
cdd_sync_crate_fixer helpers to Python.

Entry point: run_sync(config, log_callback=None)
"""

from __future__ import annotations

import logging
import os
import threading
from collections import defaultdict
from pathlib import Path
from typing import Callable, Dict, List, Optional

from config import SyncConfig
from core.path_utils import normalize_for_dedup, normalize_path_for_database, resolve_serato_path
from core.serato_parser import Crate, SeratoDatabase, read_crate, write_crate
from core.session_log import session_log_file
from sync.backup import create_backup
from sync.database_fixer import update_paths
from sync.dupe_mover import (
    describe_dupe_path,
    group_duplicates,
    preview_duplicate_groups,
    resolve_keep_and_move,
    scan_and_move_duplicates,
)
from sync.media_library import MediaLibrary
from sync.pref_sorter import sort_crates

logger = logging.getLogger("cdd_sync")

_LIST_PREVIEW_LIMIT = 200


def _log_track_lines(
    _log: Callable[[str], None],
    header: str,
    names: List[str],
    cap: int = _LIST_PREVIEW_LIMIT,
) -> None:
    """Emit *header*, then one indented line per track name (capped to avoid flooding)."""
    _log(header)
    shown = names[:cap]
    for name in shown:
        _log(f"    • {name}")
    remaining = len(names) - len(shown)
    if remaining > 0:
        _log(f"    … +{remaining} more (truncated)")


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def run_sync(
    config: SyncConfig,
    log_callback: Optional[Callable[[str], None]] = None,
    cancel_event: Optional[threading.Event] = None,
) -> None:
    """
    Execute the full sync pipeline in the same sequence as Java's runSync().
    All write operations are guarded by config.dry_run. Wraps _run_sync in a
    session log file so every run — GUI or CLI — leaves a record on disk.
    """
    with session_log_file("sync", config.serato_library_path):
        _run_sync(config, log_callback, cancel_event)


def _run_sync(
    config: SyncConfig,
    log_callback: Optional[Callable[[str], None]] = None,
    cancel_event: Optional[threading.Event] = None,
) -> None:
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    serato_path = config.serato_library_path

    _log("cdd-sync-pro started")

    # ── Backup ───────────────────────────────────────────────────────────────
    if config.backup_enabled:
        if config.dry_run:
            _log(f"[DRY RUN] Would have: created backup of {serato_path}")
        else:
            backup_result = create_backup(serato_path)
            if backup_result is None:
                _log("Backup failed. Aborting sync for safety.")
                return

    # ── Scan media library ───────────────────────────────────────────────────
    _log(f"Scanning media library {config.music_library_path}...")
    fs_library = MediaLibrary.read_from(config.music_library_path)
    if fs_library.total_tracks() <= 0:
        _log("Unable to find any supported files in your media library directory.")
        _log("Are you sure you specified the right path in the config file?")
        return
    _log(f"Found {fs_library.total_tracks()} tracks in {fs_library.total_directories()} directories")

    # ── Step 0 (early): Duplicate move ──────────────────────────────────────
    if config.step0_enabled:
        if config.dupe_move_enabled:
            if config.dry_run:
                _dry_run_step0_move(
                    fs_library, config.music_library_path, serato_path,
                    config.dupe_detection_mode, config.dupe_move_mode, _log,
                )
            else:
                moved_to_kept = scan_and_move_duplicates(
                    config.music_library_path, fs_library,
                    config.dupe_detection_mode, config.dupe_move_mode,
                    crate_index=build_crate_membership_index(serato_path),
                    log_callback=_log,
                )
                if moved_to_kept:
                    db_path = os.path.join(serato_path, "database V2")
                    db_updated = update_paths(db_path, moved_to_kept)
                    if db_updated > 0:
                        _log(f"Updated {db_updated} paths in database V2 for moved duplicates")
                    _log("Rescanning media library after duplicate removal...")
                    fs_library = MediaLibrary.read_from(config.music_library_path)
                    _log(f"Found {fs_library.total_tracks()} tracks remaining.")
    else:
        _log("Step 0 skipped: duplicate management toggle is off.")

    # ── Validate / create serato_library_path ───────────────────────────────
    _log(f"Writing files into serato library {serato_path}...")
    if not Path(serato_path).is_dir():
        if config.dry_run:
            _log(f"[DRY RUN] Would have: created Serato library folder {serato_path}")
        else:
            try:
                Path(serato_path).mkdir(parents=True, exist_ok=True)
                _log(f"Created Serato library folder: {serato_path}")
            except OSError as exc:
                _log(f"Failed to create Serato library folder: {serato_path} — {exc}")
                return

    # ── Load Serato database ─────────────────────────────────────────────────
    db_file = Path(serato_path) / "database V2"
    database: Optional[SeratoDatabase] = None
    if db_file.exists():
        try:
            database = SeratoDatabase.read_from(db_file)
        except Exception as exc:
            _log(f"Could not parse database V2: {exc}")
    else:
        _log("No existing Serato database found. Skipping path normalization.")

    # ── Validate parent crate ────────────────────────────────────────────────
    parent_crate_path = config.parent_crate_path
    if parent_crate_path:
        _log(f"Using parent crate: {parent_crate_path}")
        subcrates_dir = Path(serato_path) / "Subcrates"
        parent_crate_file = subcrates_dir / f"{parent_crate_path}.crate"
        if not parent_crate_file.exists():
            _log(f"Parent crate '{parent_crate_path}' does not exist. Creating it automatically...")
            if config.dry_run:
                _log(f"[DRY RUN] Would have: created parent crate {parent_crate_path}")
            else:
                try:
                    parent_crate_file.parent.mkdir(parents=True, exist_ok=True)
                    write_crate(Crate(), parent_crate_file)
                except Exception as exc:
                    _log(f"Failed to create parent crate '{parent_crate_path}': {exc}")
                    return

        # Check for duplicate parent crate names
        if subcrates_dir.is_dir():
            count = sum(
                1 for f in subcrates_dir.iterdir()
                if f.name.lower() == f"{parent_crate_path.lower()}.crate"
            )
            if count > 1:
                _log(f"Duplicate parent crate detected: found {count} crates named '{parent_crate_path}'.")
                _log("Please resolve the duplication in Serato before syncing.")
                return

    # ── Load track index (database ref for path encoding) ───────────────────
    # Python: track index == the SeratoDatabase already loaded above.

    # ── Clear library if configured ──────────────────────────────────────────
    if config.clear_library_before_sync:
        if config.dry_run:
            _log("[DRY RUN] Would have: cleared existing Serato library")
        else:
            _delete_dir_contents(Path(serato_path) / "Crates")
            _delete_dir_contents(Path(serato_path) / "Subcrates")
            db_file = Path(serato_path) / "database V2"
            if db_file.exists():
                db_file.unlink()

    # ── Step 1: Fix broken paths in database V2 ──────────────────────────────
    if not config.clear_library_before_sync and config.step1_enabled:
        if config.dry_run:
            _dry_run_step1(serato_path, fs_library, _log)
        else:
            _log("Step 1: Updating broken paths in database V2...")
            update_database_paths(serato_path, fs_library, _log, cancel_event=cancel_event)
            # Reload database — Step 1 patched paths on disk; the in-memory object
            # is now stale. Steps 2–4 rely on it for resolve_serato_path() lookups,
            # so they must see the post-patch state or they'll write old broken paths.
            if db_file.exists():
                try:
                    database = SeratoDatabase.read_from(db_file)
                except Exception as exc:
                    _log(f"Warning: could not reload database after Step 1: {exc}")
    elif not config.step1_enabled:
        _log("Step 1 skipped: step1 toggle is off.")
    else:
        _log("Step 1 skipped: Clear Library is on (database was deleted.)")

    # ── Step 2: Fix broken paths in existing crates ───────────────────────────
    if not config.clear_library_before_sync and config.step2_enabled:
        if config.dry_run:
            _dry_run_step2(serato_path, fs_library, database, _log)
        else:
            _log("Step 2: Rewriting crate paths from filesystem...")
            fix_existing_crates(serato_path, fs_library, database, _log)
    elif not config.step2_enabled:
        _log("Step 2 skipped: step2 toggle is off.")
    else:
        _log("Step 2 skipped: Clear Library is on (crates were deleted.)")

    # ── Step 3: Append new tracks to existing crates ─────────────────────────
    if config.step3_enabled:
        if config.dry_run:
            _dry_run_step3(serato_path, fs_library, parent_crate_path, _log)
        else:
            _log("Step 3: Appending new tracks to existing crates...")
            append_new_tracks(serato_path, fs_library, parent_crate_path, database, _log)
    else:
        _log("Step 3 skipped: step3 toggle is off.")

    # ── Step 4: Create new crates for new library paths ──────────────────────
    if config.step4_enabled:
        if config.dry_run:
            _dry_run_step4(serato_path, fs_library, parent_crate_path, _log)
        else:
            _log("Step 4: Creating new crates for new library paths...")
            create_new_crates(serato_path, fs_library, parent_crate_path, database, _log)
    else:
        _log("Step 4 skipped: step4 toggle is off.")

    # ── Step 0 (late): Log-only duplicate scan ───────────────────────────────
    if config.dupe_scan_enabled and not config.dupe_move_enabled and config.step0_enabled:
        _scan_and_log_duplicates(fs_library, config.music_library_path, serato_path, config.dupe_detection_mode, _log)

    _log("Sync Complete")

    # ── Sort crates ───────────────────────────────────────────────────────────
    if config.crate_sorting_enabled:
        if config.dry_run:
            _log("[DRY RUN] Would have: sorted crates alphabetically in neworder.pref")
        else:
            sort_crates(serato_path)

    if config.dry_run:
        _log("[DRY RUN] Sync complete — no files were written.")


# ---------------------------------------------------------------------------
# Individual step runners (for GUI per-step ▶ buttons)
# ---------------------------------------------------------------------------

def run_step0(config: SyncConfig, log_callback: Optional[Callable[[str], None]] = None) -> None:
    """Run Step 0 (Duplicate Management) in isolation. Honors config.dry_run."""
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    _log(f"Scanning media library {config.music_library_path}...")
    fs_library = MediaLibrary.read_from(config.music_library_path)
    if fs_library.total_tracks() <= 0:
        _log("Step 0: No supported files found in media library — skipping.")
        return
    _log(f"Found {fs_library.total_tracks()} tracks in {fs_library.total_directories()} directories")

    if not config.dupe_move_enabled and not config.dupe_scan_enabled:
        _log("Step 0: Duplicate scan and move are both disabled — nothing to do.")
        return

    if config.dupe_move_enabled:
        if config.dry_run:
            _dry_run_step0_move(
                fs_library, config.music_library_path, config.serato_library_path,
                config.dupe_detection_mode, config.dupe_move_mode, _log,
            )
            return
        crate_index = build_crate_membership_index(config.serato_library_path)
        moved_to_kept = scan_and_move_duplicates(
            config.music_library_path, fs_library,
            config.dupe_detection_mode, config.dupe_move_mode,
            log_callback=_log, crate_index=crate_index,
        )
        if moved_to_kept:
            db_path = os.path.join(config.serato_library_path, "database V2")
            db_updated = update_paths(db_path, moved_to_kept)
            if db_updated > 0:
                _log(f"Step 0: Updated {db_updated} paths in database V2 for moved duplicates")

            # Standalone run — Steps 1-4 won't follow automatically, so re-point any
            # crates that referenced the moved-away files ourselves (mirrors what
            # a full sync would do via Step 2 right after Step 0).
            _log("Step 0: Rescanning media library and fixing affected crate paths...")
            fs_library = MediaLibrary.read_from(config.music_library_path)
            db_file = Path(config.serato_library_path) / "database V2"
            database = SeratoDatabase.read_from(db_file) if db_file.exists() else None
            fix_existing_crates(config.serato_library_path, fs_library, database, _log)
        return

    # dupe_scan_enabled only — log-only report, no moves either way
    if config.dry_run:
        _dry_run_step0_scan(fs_library, config.music_library_path, config.serato_library_path, config.dupe_detection_mode, _log)
    else:
        _scan_and_log_duplicates(
            fs_library, config.music_library_path, config.serato_library_path, config.dupe_detection_mode, _log,
        )


def build_crate_membership_index(serato_path: str) -> Dict[str, List[str]]:
    """Map lowercase filename -> sorted list of crate display names that reference it."""
    crate_files: List[Path] = []
    collect_crate_files(Path(serato_path) / "Crates", crate_files)
    collect_crate_files(Path(serato_path) / "Subcrates", crate_files)

    index: Dict[str, set] = defaultdict(set)
    for crate_file in crate_files:
        try:
            crate = read_crate(crate_file)
        except Exception:
            continue
        crate_name = crate_file.stem
        for track_path in crate.tracks:
            index[os.path.basename(track_path).lower()].add(crate_name)

    return {k: sorted(v) for k, v in index.items()}


def _dry_run_step0_move(
    library: MediaLibrary,
    music_library_root: str,
    serato_path: str,
    detection_mode: str,
    move_mode: str,
    _log: Callable[[str], None],
) -> None:
    _log(f"[DRY RUN] Step 0: Checking for duplicates to move (detection={detection_mode}, move={move_mode})...")
    dupe_groups = preview_duplicate_groups(library, detection_mode)
    if not dupe_groups:
        _log("[DRY RUN] Step 0: No duplicates found — nothing would be moved.")
        return

    crate_index = build_crate_membership_index(serato_path)
    total_would_move = 0
    for group_key, paths in dupe_groups.items():
        kept_path, move_paths = resolve_keep_and_move(paths, move_mode)
        total_would_move += len(move_paths)
        descriptions = [describe_dupe_path(p, music_library_root, crate_index) for p in move_paths]
        _log_track_lines(
            _log,
            f"[DRY RUN] Step 0: '{os.path.basename(kept_path)}' — would keep "
            f"{describe_dupe_path(kept_path, music_library_root, crate_index)}, "
            f"move {len(move_paths)} duplicate(s):",
            descriptions,
        )
    _log(f"[DRY RUN] Step 0: Would move {total_would_move} duplicate file(s) across {len(dupe_groups)} group(s).")


def _dry_run_step0_scan(
    library: MediaLibrary,
    music_library_root: str,
    serato_path: str,
    detection_mode: str,
    _log: Callable[[str], None],
) -> None:
    _log(f"[DRY RUN] Step 0: Scanning for hard drive duplicates (detection={detection_mode})...")
    dupe_groups = preview_duplicate_groups(library, detection_mode)
    if not dupe_groups:
        _log("[DRY RUN] Step 0: No hard drive duplicates found.")
        return
    crate_index = build_crate_membership_index(serato_path)
    _log(f"[DRY RUN] Step 0: Found {len(dupe_groups)} duplicate file group(s) on hard drive:")
    for group_key, paths in dupe_groups.items():
        descriptions = [describe_dupe_path(p, music_library_root, crate_index) for p in paths]
        _log_track_lines(_log, f"    Group '{os.path.basename(group_key)}' — {len(paths)} copies:", descriptions)


def run_step1(
    config: SyncConfig,
    log_callback: Optional[Callable[[str], None]] = None,
    cancel_event: Optional[threading.Event] = None,
) -> None:
    """Run Step 1 (Fix database V2 paths) in isolation."""
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    serato_path = config.serato_library_path
    if not Path(serato_path).is_dir():
        _log(f"Step 1: Serato library path does not exist: {serato_path}")
        return

    _log(f"Scanning media library {config.music_library_path}...")
    fs_library = MediaLibrary.read_from(config.music_library_path)
    if fs_library.total_tracks() <= 0:
        _log("Step 1: No supported files found in media library — skipping.")
        return
    _log(f"Found {fs_library.total_tracks()} tracks in {fs_library.total_directories()} directories")

    if cancel_event and cancel_event.is_set():
        _log("[CANCELLED] Step 1 aborted before database scan.")
        return

    if config.dry_run:
        _dry_run_step1(serato_path, fs_library, _log)
    else:
        _log("Step 1: Updating broken paths in database V2...")
        update_database_paths(serato_path, fs_library, _log, cancel_event=cancel_event)


def run_step2(config: SyncConfig, log_callback: Optional[Callable[[str], None]] = None) -> None:
    """Run Step 2 (Fix existing crate paths) in isolation."""
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    serato_path = config.serato_library_path
    if not Path(serato_path).is_dir():
        _log(f"Step 2: Serato library path does not exist: {serato_path}")
        return

    _log(f"Scanning media library {config.music_library_path}...")
    fs_library = MediaLibrary.read_from(config.music_library_path)
    if fs_library.total_tracks() <= 0:
        _log("Step 2: No supported files found in media library — skipping.")
        return
    _log(f"Found {fs_library.total_tracks()} tracks in {fs_library.total_directories()} directories")

    db_file = Path(serato_path) / "database V2"
    database: Optional[SeratoDatabase] = None
    if db_file.exists():
        try:
            database = SeratoDatabase.read_from(db_file)
        except Exception as exc:
            _log(f"Step 2: Could not parse database V2: {exc}")

    if config.dry_run:
        _dry_run_step2(serato_path, fs_library, database, _log)
    else:
        _log("Step 2: Rewriting crate paths from filesystem...")
        fix_existing_crates(serato_path, fs_library, database, _log)


def run_step3(config: SyncConfig, log_callback: Optional[Callable[[str], None]] = None) -> None:
    """Run Step 3 (Append new tracks to existing crates) in isolation."""
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    serato_path = config.serato_library_path
    _log(f"Scanning media library {config.music_library_path}...")
    fs_library = MediaLibrary.read_from(config.music_library_path)
    if fs_library.total_tracks() <= 0:
        _log("Step 3: No supported files found in media library — skipping.")
        return
    _log(f"Found {fs_library.total_tracks()} tracks in {fs_library.total_directories()} directories")

    db_file = Path(serato_path) / "database V2"
    database: Optional[SeratoDatabase] = None
    if db_file.exists():
        try:
            database = SeratoDatabase.read_from(db_file)
        except Exception as exc:
            _log(f"Step 3: Could not parse database V2: {exc}")

    if config.dry_run:
        _dry_run_step3(serato_path, fs_library, config.parent_crate_path, _log)
    else:
        _log("Step 3: Appending new tracks to existing crates...")
        append_new_tracks(serato_path, fs_library, config.parent_crate_path, database, _log)


def run_step4(config: SyncConfig, log_callback: Optional[Callable[[str], None]] = None) -> None:
    """Run Step 4 (Create new crates for new library paths) in isolation."""
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    serato_path = config.serato_library_path
    _log(f"Scanning media library {config.music_library_path}...")
    fs_library = MediaLibrary.read_from(config.music_library_path)
    if fs_library.total_tracks() <= 0:
        _log("Step 4: No supported files found in media library — skipping.")
        return
    _log(f"Found {fs_library.total_tracks()} tracks in {fs_library.total_directories()} directories")

    db_file = Path(serato_path) / "database V2"
    database: Optional[SeratoDatabase] = None
    if db_file.exists():
        try:
            database = SeratoDatabase.read_from(db_file)
        except Exception as exc:
            _log(f"Step 4: Could not parse database V2: {exc}")

    if config.dry_run:
        _dry_run_step4(serato_path, fs_library, config.parent_crate_path, _log)
    else:
        _log("Step 4: Creating new crates for new library paths...")
        create_new_crates(serato_path, fs_library, config.parent_crate_path, database, _log)


# ---------------------------------------------------------------------------
# Step 1 helper — update_database_paths
# ---------------------------------------------------------------------------

def update_database_paths(
    serato_path: str,
    library: MediaLibrary,
    _log: Optional[Callable[[str], None]] = None,
    cancel_event: Optional[threading.Event] = None,
) -> None:
    """Fix broken pfil paths in database V2 by filename lookup against the library."""
    def __log(msg):
        logger.info(msg)
        if _log:
            _log(msg)

    db_file = Path(serato_path) / "database V2"
    if not db_file.exists():
        __log("Step 1: No Serato database V2 found — skipping.")
        return

    try:
        database = SeratoDatabase.read_from(db_file)
    except Exception as exc:
        __log(f"Step 1: Failed to read database V2: {exc}")
        return

    volume_root = get_volume_root(serato_path)
    lib_index = build_library_index(library)

    if not lib_index:
        __log("Step 1: Media library is empty — skipping.")
        return

    if cancel_event and cancel_event.is_set():
        __log("[CANCELLED] Step 1: aborted before path scan.")
        return

    path_fixes: Dict[str, str] = {}
    all_paths = list(database.get_all_track_paths())
    __log(f"Step 1: Scanning {len(all_paths)} tracks in database V2...")
    for i, db_track_path in enumerate(all_paths):
        if cancel_event and cancel_event.is_set():
            __log(f"[CANCELLED] Step 1: path scan stopped at {i}/{len(all_paths)}.")
            return
        if volume_root:
            abs_path = os.path.join(volume_root, db_track_path)
            if os.path.exists(abs_path):
                continue  # still valid

        filename = os.path.basename(db_track_path).lower()
        candidates = lib_index.get(filename)
        if not candidates:
            continue
        if len(candidates) > 1:
            logger.debug("Ambiguous: %d candidates for %s", len(candidates), filename)
            continue

        fixed = normalize_path_for_database(candidates[0])
        if fixed != db_track_path:
            path_fixes[db_track_path] = fixed

    if not path_fixes:
        __log("Step 1: No broken paths found in database V2.")
        return

    if cancel_event and cancel_event.is_set():
        __log("[CANCELLED] Step 1: aborted before writing fixes.")
        return

    fix_names = [os.path.basename(new) for new in path_fixes.values()]
    _log_track_lines(__log, f"Step 1: Fixing {len(path_fixes)} broken paths in database V2:", fix_names)
    updated = update_paths(
        str(db_file),
        path_fixes,
        cancel_event=cancel_event,
        log_callback=_log,
    )
    __log(f"Step 1 complete: updated {updated} paths in database V2.")


# ---------------------------------------------------------------------------
# Step 2 helper — fix_existing_crates
# ---------------------------------------------------------------------------

def fix_existing_crates(
    serato_path: str,
    library: MediaLibrary,
    database: Optional[SeratoDatabase],
    _log: Optional[Callable[[str], None]] = None,
) -> None:
    """Rewrite track paths in existing .crate files using the filesystem as truth."""
    def __log(msg):
        logger.info(msg)
        if _log:
            _log(msg)

    lib_index = build_library_index(library)
    if not lib_index:
        __log("Step 2: Media library is empty — skipping.")
        return

    crate_files: List[Path] = []
    collect_crate_files(Path(serato_path) / "Crates", crate_files)
    collect_crate_files(Path(serato_path) / "Subcrates", crate_files)

    if not crate_files:
        __log("Step 2: No crate files found in Crates/ or Subcrates/.")
        return

    total_crates = len(crate_files)
    __log(f"Step 2: Inspecting {total_crates} crate files for broken paths...")

    fixed_crates = 0
    fixed_paths = 0
    read_errors = 0
    write_errors = 0

    for idx, crate_file in enumerate(crate_files, start=1):
        try:
            crate = read_crate(crate_file)
        except Exception as exc:
            logger.error("Failed to read crate: %s — %s", crate_file.name, exc)
            read_errors += 1
            continue

        original = list(crate.tracks)
        updated: List[str] = []
        changed = False
        crate_fixes = 0
        fixed_names: List[str] = []

        for track_path in original:
            filename = os.path.basename(track_path).lower()
            candidates = lib_index.get(filename)
            if candidates:
                resolved = resolve_serato_path(candidates[0], database)
                new_rel = normalize_path_for_database(resolved)
                if new_rel != track_path:
                    logger.debug("Step 2 [%s]: fixing path %s → %s",
                                 crate_file.stem, track_path, new_rel)
                    updated.append(new_rel)
                    changed = True
                    fixed_paths += 1
                    crate_fixes += 1
                    fixed_names.append(os.path.basename(new_rel))
                else:
                    updated.append(track_path)
            else:
                updated.append(track_path)

        if changed:
            crate.set_tracks_raw(updated)
            try:
                write_crate(crate, crate_file)
                fixed_crates += 1
                _log_track_lines(
                    __log,
                    f"Step 2 [{idx}/{total_crates}]: {crate_file.stem} — {crate_fixes} path(s) fixed:",
                    fixed_names,
                )
            except Exception as exc:
                logger.error("Failed to write crate: %s — %s", crate_file.name, exc)
                write_errors += 1
        else:
            logger.debug("Step 2 [%d/%d]: %s — no changes", idx, total_crates, crate_file.stem)

    summary_parts = [f"{fixed_paths} path(s) fixed", f"across {fixed_crates} crate(s)"]
    if read_errors:
        summary_parts.append(f"{read_errors} read error(s)")
    if write_errors:
        summary_parts.append(f"{write_errors} write error(s)")
    clean = total_crates - fixed_crates - read_errors
    summary_parts.append(f"{clean} already clean")
    __log(f"Step 2 complete: {', '.join(summary_parts)}.")


# ---------------------------------------------------------------------------
# Step 3 helper — append_new_tracks
# ---------------------------------------------------------------------------

def append_new_tracks(
    serato_path: str,
    library: MediaLibrary,
    parent_crate_path: Optional[str],
    database: Optional[SeratoDatabase],
    _log: Optional[Callable[[str], None]] = None,
) -> None:
    """Append new tracks to existing crate files (never create new crates)."""
    def __log(msg):
        logger.info(msg)
        if _log:
            _log(msg)

    subcrates_dir = Path(serato_path) / "Subcrates"
    if not subcrates_dir.exists():
        __log("Step 3: No Subcrates directory found — skipping.")
        return

    crate_map = build_crate_file_map(library, parent_crate_path)
    total = len(crate_map)
    __log(f"Step 3: Checking {total} library folder(s) against existing crates...")

    appended = 0
    new_tracks_total = 0
    skipped = 0
    missing = 0

    for crate_filename, new_tracks in crate_map.items():
        crate_file = subcrates_dir / crate_filename
        if not crate_file.exists():
            missing += 1
            continue  # Step 4 will create it

        try:
            crate = read_crate(crate_file)
        except Exception as exc:
            logger.error("Failed to read crate for append: %s — %s", crate_filename, exc)
            continue

        existing_keys = set(normalize_for_dedup(t) for t in crate.tracks)
        actually_new = [t for t in new_tracks if normalize_for_dedup(t) not in existing_keys]

        before = len(crate.tracks)
        if database:
            crate.set_database(database)
        for track in new_tracks:
            crate.add_track(track)
        after = len(crate.tracks)
        added = after - before

        if added > 0:
            try:
                write_crate(crate, crate_file)
                appended += 1
                new_tracks_total += added
                added_names = [os.path.basename(t) for t in actually_new]
                _log_track_lines(
                    __log,
                    f"Step 3: {crate_filename.removesuffix('.crate')} — +{added} track(s) appended ({after} total):",
                    added_names,
                )
            except Exception as exc:
                logger.error("Failed to write appended crate: %s — %s", crate_filename, exc)
        else:
            skipped += 1
            logger.debug("Step 3: %s — no new tracks", crate_filename)

    __log(
        f"Step 3 complete: {new_tracks_total} track(s) appended across {appended} crate(s) — "
        f"{skipped} unchanged, {missing} not yet created (Step 4 will handle)."
    )


# ---------------------------------------------------------------------------
# Step 4 helper — create_new_crates
# ---------------------------------------------------------------------------

def create_new_crates(
    serato_path: str,
    library: MediaLibrary,
    parent_crate_path: Optional[str],
    database: Optional[SeratoDatabase],
    _log: Optional[Callable[[str], None]] = None,
) -> None:
    """Create new .crate files for library folders that have no matching crate yet."""
    def __log(msg):
        logger.info(msg)
        if _log:
            _log(msg)

    subcrates_dir = Path(serato_path) / "Subcrates"
    crate_map = build_crate_file_map(library, parent_crate_path)
    total = len(crate_map)
    __log(f"Step 4: {total} library folder(s) mapped — checking for missing crates...")

    created = 0
    skipped = 0
    empty_skipped = 0
    errors = 0

    for crate_filename, tracks in crate_map.items():
        crate_file = subcrates_dir / crate_filename
        if crate_file.exists():
            skipped += 1
            logger.debug("Step 4: %s already exists — skipping", crate_filename)
            continue

        if not tracks:
            empty_skipped += 1
            logger.debug("Step 4: %s has no direct tracks — skipping (no empty crate created)", crate_filename)
            continue

        crate_display = crate_filename.removesuffix(".crate")
        track_names = [os.path.basename(t) for t in tracks]
        _log_track_lines(__log, f"Step 4: Creating '{crate_display}' ({len(tracks)} track(s)):", track_names)

        crate = Crate()
        if database:
            crate.set_database(database)
        crate.add_tracks(tracks)

        try:
            crate_file.parent.mkdir(parents=True, exist_ok=True)
            write_crate(crate, crate_file)
            created += 1
            __log(f"Step 4: ✓ Created '{crate_display}'")
        except Exception as exc:
            logger.error("Failed to write new crate: %s — %s", crate_filename, exc)
            __log(f"Step 4: ✗ Failed to create '{crate_display}': {exc}")
            errors += 1

    summary_parts = [f"{created} crate(s) created", f"{skipped} already existed"]
    if empty_skipped:
        summary_parts.append(f"{empty_skipped} empty folder(s) skipped")
    if errors:
        summary_parts.append(f"{errors} error(s)")
    __log(f"Step 4 complete: {', '.join(summary_parts)}.")



# ---------------------------------------------------------------------------
# Dry-run preview helpers — read-only analysis with real counts
# ---------------------------------------------------------------------------

def _dry_run_step1(serato_path: str, library: MediaLibrary, _log) -> None:
    _log("[DRY RUN] Step 1: Checking database V2 for broken paths...")
    db_file = Path(serato_path) / "database V2"
    if not db_file.exists():
        _log("[DRY RUN] Step 1: No database V2 found — nothing to fix.")
        return
    try:
        database = SeratoDatabase.read_from(db_file)
    except Exception as exc:
        _log(f"[DRY RUN] Step 1: Could not read database V2: {exc}")
        return
    volume_root = get_volume_root(serato_path)
    lib_index = build_library_index(library)
    broken_names: List[str] = []
    ambiguous = 0
    for db_track_path in database.get_all_track_paths():
        if volume_root:
            if os.path.exists(os.path.join(volume_root, db_track_path)):
                continue
        filename = os.path.basename(db_track_path).lower()
        candidates = lib_index.get(filename)
        if not candidates:
            continue
        if len(candidates) > 1:
            ambiguous += 1
            continue
        fixed = normalize_path_for_database(candidates[0])
        if fixed != db_track_path:
            broken_names.append(os.path.basename(fixed))
    if broken_names:
        _log_track_lines(
            _log,
            f"[DRY RUN] Step 1: Would fix {len(broken_names)} broken paths in database V2 "
            f"({ambiguous} ambiguous skipped):",
            broken_names,
        )
    else:
        _log(f"[DRY RUN] Step 1: Would fix 0 broken paths in database V2 ({ambiguous} ambiguous skipped).")


def _dry_run_step2(serato_path: str, library: MediaLibrary, database, _log) -> None:
    _log("[DRY RUN] Step 2: Checking existing crate files for broken paths...")
    lib_index = build_library_index(library)
    crate_files: List[Path] = []
    collect_crate_files(Path(serato_path) / "Crates", crate_files)
    collect_crate_files(Path(serato_path) / "Subcrates", crate_files)
    if not crate_files:
        _log("[DRY RUN] Step 2: No crate files found.")
        return
    _log(f"[DRY RUN] Step 2: Inspecting {len(crate_files)} crate files...")
    would_fix_paths = 0
    would_fix_crates = 0
    for crate_file in crate_files:
        try:
            crate = read_crate(crate_file)
        except Exception:
            continue
        crate_fix_names: List[str] = []
        for track_path in crate.tracks:
            filename = os.path.basename(track_path).lower()
            candidates = lib_index.get(filename)
            if candidates:
                resolved = resolve_serato_path(candidates[0], database)
                new_rel = normalize_path_for_database(resolved)
                if new_rel != track_path:
                    crate_fix_names.append(os.path.basename(new_rel))
        if crate_fix_names:
            would_fix_crates += 1
            would_fix_paths += len(crate_fix_names)
            _log_track_lines(
                _log,
                f"[DRY RUN] Step 2: {crate_file.stem} — {len(crate_fix_names)} path(s) would be fixed:",
                crate_fix_names,
            )
    _log(f"[DRY RUN] Step 2: Would fix {would_fix_paths} paths across {would_fix_crates} crates.")


def _dry_run_step3(serato_path: str, library: MediaLibrary, parent_crate_path, _log) -> None:
    _log("[DRY RUN] Step 3: Checking for new tracks to append to existing crates...")
    subcrates_dir = Path(serato_path) / "Subcrates"
    if not subcrates_dir.exists():
        _log("[DRY RUN] Step 3: No Subcrates directory — nothing to append.")
        return
    crate_map = build_crate_file_map(library, parent_crate_path)
    would_update = 0
    would_add = 0
    for crate_filename, new_tracks in crate_map.items():
        crate_file = subcrates_dir / crate_filename
        if not crate_file.exists():
            continue
        try:
            crate = read_crate(crate_file)
        except Exception:
            continue
        existing = set(normalize_for_dedup(t) for t in crate.tracks)
        new = [t for t in new_tracks if normalize_for_dedup(t) not in existing]
        if new:
            would_update += 1
            would_add += len(new)
            new_names = [os.path.basename(t) for t in new]
            _log_track_lines(
                _log,
                f"[DRY RUN] Step 3: {crate_filename.removesuffix('.crate')} — "
                f"+{len(new)} track(s) would be appended:",
                new_names,
            )
    _log(f"[DRY RUN] Step 3: Would append {would_add} new tracks across {would_update} existing crates.")


def _dry_run_step4(serato_path: str, library: MediaLibrary, parent_crate_path, _log) -> None:
    _log("[DRY RUN] Step 4: Checking for new library folders that need crates...")
    subcrates_dir = Path(serato_path) / "Subcrates"
    crate_map = build_crate_file_map(library, parent_crate_path)
    would_create: List[tuple] = []
    already_exist = 0
    empty_skipped = 0
    for crate_filename, tracks in crate_map.items():
        if (subcrates_dir / crate_filename).exists():
            already_exist += 1
        elif not tracks:
            empty_skipped += 1
        else:
            would_create.append((crate_filename.removesuffix(".crate"), tracks))
    for name, tracks in would_create:
        track_names = [os.path.basename(t) for t in tracks]
        _log_track_lines(_log, f"[DRY RUN] Step 4: Would create '{name}' ({len(tracks)} track(s)):", track_names)
    summary = f"[DRY RUN] Step 4: Would create {len(would_create)} new crates ({already_exist} already exist"
    if empty_skipped:
        summary += f", {empty_skipped} empty folder(s) skipped"
    _log(summary + ").")


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

def build_library_index(library: MediaLibrary) -> Dict[str, List[str]]:
    """Map lowercase filename → list of absolute paths from the scanned library."""
    index: Dict[str, List[str]] = defaultdict(list)
    for path in library.flatten_tracks():
        filename = os.path.basename(path).lower()
        index[filename].append(path)
    return dict(index)


def build_crate_file_map(
    library: MediaLibrary,
    parent_crate_path: Optional[str],
) -> Dict[str, List[str]]:
    """Map crate filename (e.g. 'Parent%%Child.crate') → direct track list for that folder."""
    result: Dict[str, List[str]] = {}
    root_name = parent_crate_path if parent_crate_path else ""
    _build_crate_file_map_recursive(library, root_name, 0, result)
    return result


def _build_crate_file_map_recursive(
    node: MediaLibrary,
    crate_name: str,
    level: int,
    result: Dict[str, List[str]],
) -> None:
    if level > 0:
        result[f"{crate_name}.crate"] = list(node.tracks)
    for child in node.children:
        child_name = (
            child.directory if not crate_name
            else f"{crate_name}%%{child.directory}"
        )
        _build_crate_file_map_recursive(child, child_name, level + 1, result)


def collect_crate_files(directory: Path, result: List[Path]) -> None:
    """Recursively collect all .crate files under *directory* into *result*."""
    if not directory.is_dir():
        return
    for entry in directory.iterdir():
        if entry.is_dir():
            collect_crate_files(entry, result)
        elif entry.suffix == ".crate":
            result.append(entry)


def get_volume_root(serato_path: str) -> Optional[str]:
    """
    Return the parent directory of _Serato_, or None if path doesn't end in _Serato_.
    e.g. /Volumes/Current/_Serato_ → /Volumes/Current
    """
    p = Path(serato_path)
    if p.name.lower() == "_serato_":
        return str(p.parent)
    return None


# ---------------------------------------------------------------------------
# Late Step 0 — log-only dupe scan
# ---------------------------------------------------------------------------

def _scan_and_log_duplicates(
    library: MediaLibrary,
    music_library_root: str,
    serato_path: str,
    detection_mode: str = "name-and-size",
    _log: Optional[Callable[[str], None]] = None,
) -> None:
    def __log(msg: str) -> None:
        logger.info(msg)
        if _log:
            _log(msg)

    __log("Step 0: Scanning for hard drive duplicates...")
    dupe_groups = group_duplicates(library.flatten_tracks(), detection_mode)

    if not dupe_groups:
        __log("Step 0: No hard drive duplicates found.")
        return

    crate_index = build_crate_membership_index(serato_path)
    __log(f"Step 0: Found {len(dupe_groups)} duplicate file group(s) on hard drive:")
    for group_key, paths in dupe_groups.items():
        descriptions = [describe_dupe_path(p, music_library_root, crate_index) for p in paths]
        _log_track_lines(__log, f"    Group '{os.path.basename(group_key)}' — {len(paths)} copies:", descriptions)


# ---------------------------------------------------------------------------
# Utility
# ---------------------------------------------------------------------------

def _delete_dir_contents(directory: Path) -> None:
    """Delete all files (non-recursively) inside *directory*, if it exists."""
    if not directory.is_dir():
        return
    for entry in directory.iterdir():
        if entry.is_file():
            try:
                entry.unlink()
            except OSError as exc:
                logger.error("Failed to delete %s: %s", entry, exc)
