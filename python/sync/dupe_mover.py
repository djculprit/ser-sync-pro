"""
DupeMover — scans for duplicate tracks and moves copies to a timestamped folder.

Mirrors Java's cdd_sync_dupe_mover.scanAndMoveDuplicates():
  - Detection keys: "name-and-size", "name-only", "off"
  - Move modes: "keep-newest" (move older), "keep-oldest" (move newer)
  - Destination: <library_parent>/cdd-sync-pro/dupes/<timestamp>/
  - Writes dupes.log matching Java's writeLogFile() format
"""

from __future__ import annotations

import logging
import os
import shutil
from datetime import datetime
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

from sync.media_library import MediaLibrary

logger = logging.getLogger("cdd_sync")

_DUPES_FOLDER = "cdd-sync-pro/dupes"
_KEEP_NEWEST = "keep-newest"
_KEEP_OLDEST = "keep-oldest"


def group_by_key(all_tracks: List[str], detection_mode: str) -> Dict[str, List[str]]:
    """Group *all_tracks* by detection key (singletons included; off → empty)."""
    if detection_mode == "off":
        return {}

    groups: Dict[str, List[str]] = {}
    for path in all_tracks:
        filename = os.path.basename(path).lower()
        if detection_mode == "name-only":
            key = filename
        else:
            if detection_mode not in ("name-and-size",):
                logger.error("Invalid detection mode '%s', defaulting to name-and-size", detection_mode)
            try:
                size = os.path.getsize(path)
            except OSError:
                size = 0
            key = f"{filename}|{size}"
        groups.setdefault(key, []).append(path)

    return groups


def group_duplicates(all_tracks: List[str], detection_mode: str) -> Dict[str, List[str]]:
    """Group *all_tracks* by detection key; return only groups with >1 member (off → empty)."""
    groups = group_by_key(all_tracks, detection_mode)
    return {k: v for k, v in groups.items() if len(v) > 1}


def resolve_keep_and_move(paths: List[str], move_mode: str) -> Tuple[str, List[str]]:
    """Sort a duplicate group by move_mode; return (kept_path, [paths to move])."""
    keep_newest = move_mode == _KEEP_NEWEST
    paths_sorted = sorted(paths, key=lambda p: _mtime(p), reverse=keep_newest)
    return paths_sorted[0], paths_sorted[1:]


def preview_duplicate_groups(library: MediaLibrary, detection_mode: str) -> Dict[str, List[str]]:
    """Read-only: group tracks in *library* by detection key without touching disk."""
    return group_duplicates(library.flatten_tracks(), detection_mode)


def describe_dupe_path(
    path: str,
    music_library_root: str,
    crate_index: Optional[Dict[str, List[str]]] = None,
) -> str:
    """Format a track path as '<relative path> [crates: A, B]' (or '[not in any crate]')."""
    rel = _relative_path(path, music_library_root)
    crates = crate_index.get(os.path.basename(path).lower(), []) if crate_index else []
    tag = f"crates: {', '.join(crates)}" if crates else "not in any crate"
    return f"{rel} [{tag}]"


def scan_and_move_duplicates(
    music_library_root: str,
    library: MediaLibrary,
    detection_mode: str,
    move_mode: str,
    log_callback: Optional[Callable[[str], None]] = None,
    crate_index: Optional[Dict[str, List[str]]] = None,
) -> Dict[str, str]:
    """
    Scan *library* for duplicates and move copies to a timestamped dupes folder.

    Returns a dict of {moved_path: kept_path} for database path-update use.
    """
    def _log(msg: str) -> None:
        logger.info(msg)
        if log_callback:
            log_callback(msg)

    _log(f"Duplicate detection mode: {detection_mode}")

    if detection_mode == "off":
        _log("Duplicate detection is disabled.")
        return {}

    _log("Scanning for duplicates to move...")

    if move_mode == _KEEP_NEWEST:
        _log("Move strategy: Keep newest, move older files")
    else:
        _log("Move strategy: Keep oldest, move newer files")

    # Flatten all tracks
    all_tracks: List[str] = library.flatten_tracks()
    _log(f"Total tracks scanned: {len(all_tracks)}")

    all_groups = group_by_key(all_tracks, detection_mode)
    dupe_groups = {k: v for k, v in all_groups.items() if len(v) > 1}

    if detection_mode == "name-only":
        _log(f"Total unique filenames: {len(all_groups)}")
    else:
        _log(f"Total unique filename+size combinations: {len(all_groups)}")

    if not dupe_groups:
        _log("No duplicates found.")
        return {}

    total_groups = len(dupe_groups)
    _log(f"Found {total_groups} duplicate groups.")

    # Create timestamped dupes folder
    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    library_parent = Path(music_library_root).parent
    dupes_root = library_parent / _DUPES_FOLDER / timestamp

    if dupes_root.exists():
        logger.error("Dupes folder already exists: %s", dupes_root)
        logger.error("This should not happen with timestamped folders. Aborting.")
        return {}

    try:
        dupes_root.mkdir(parents=True, exist_ok=False)
    except OSError as exc:
        logger.error("Failed to create dupes folder: %s — %s", dupes_root, exc)
        return {}

    moved_to_kept: Dict[str, str] = {}
    log_entries: List[str] = []
    total_moved = 0

    for group_key, paths in dupe_groups.items():
        kept_path, move_paths = resolve_keep_and_move(paths, move_mode)
        kept_date = datetime.fromtimestamp(_mtime(kept_path)).strftime("%Y-%m-%d")

        log_entries.append(f"Duplicate group: {group_key}")
        log_entries.append(f"  KEPT:  {kept_path} ({kept_date})")
        _log(
            f"Step 0: '{os.path.basename(kept_path)}' — {len(move_paths)} duplicate(s), keeping "
            f"{describe_dupe_path(kept_path, music_library_root, crate_index)} ({kept_date})"
        )

        for move_path in move_paths:
            move_date = datetime.fromtimestamp(_mtime(move_path)).strftime("%Y-%m-%d")
            rel = _relative_path(move_path, music_library_root)
            desc = describe_dupe_path(move_path, music_library_root, crate_index)
            dest = dupes_root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)

            try:
                shutil.move(move_path, str(dest))
                log_entries.append(f"  MOVED: {move_path} ({move_date})")
                log_entries.append(f"      -> {dest}")
                moved_to_kept[move_path] = kept_path
                total_moved += 1
                _log(f"    • moved {desc} ({move_date})")
            except OSError as exc:
                log_entries.append(f"  ERROR: Failed to move {move_path}: {exc}")
                _log(f"    • ERROR moving {desc}: {exc}")

        log_entries.append("")

    # Write dupes.log
    log_file = dupes_root / "dupes.log"
    _write_log(log_file, timestamp, total_groups, total_moved, log_entries)

    _log(f"Moved {total_moved} duplicate files to: {dupes_root}")
    _log(f"See {log_file} for details.")

    return moved_to_kept


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mtime(path: str) -> float:
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0.0


def _relative_path(file_path: str, library_root: str) -> str:
    norm_file = file_path.replace("\\", "/")
    norm_root = library_root.replace("\\", "/")
    if not norm_root.endswith("/"):
        norm_root += "/"
    if norm_file.startswith(norm_root):
        return norm_file[len(norm_root):]
    return os.path.basename(file_path)


def _write_log(
    log_file: Path,
    timestamp: str,
    total_groups: int,
    total_moved: int,
    entries: List[str],
) -> None:
    try:
        with log_file.open("w", encoding="utf-8") as fh:
            fh.write("=== Duplicate File Scan Report ===\n")
            fh.write(f"Date: {timestamp.replace('_', ' ')}\n")
            fh.write(f"Total duplicate groups found: {total_groups}\n")
            fh.write(f"Total files moved: {total_moved}\n")
            fh.write("=====================================\n\n")
            for entry in entries:
                fh.write(entry + "\n")
    except OSError as exc:
        logger.error("Failed to write dupes log: %s", exc)
