"""
session_fixer.py — Python port of session_fixer_parser.java + session_fixer_core_logic.java

Public API:
    scan_broken_paths(serato_path, music_library_paths, dry_run=True, log_callback=None) -> dict
    fix_broken_paths(serato_path, music_library_paths, dry_run=False, log_callback=None)  -> tuple[int, int]
"""

from __future__ import annotations

import os
import struct
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Callable, Optional

from sync.media_library import MediaLibrary


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_FIELD_FILEPATH = 0x02

_OENT = b"oent"
_ADAT = b"adat"
_VRSN = b"vrsn"

# ---------------------------------------------------------------------------
# Low-level helpers
# ---------------------------------------------------------------------------

def _read_int(data: bytes, offset: int) -> int:
    """Read a big-endian 4-byte signed integer."""
    return struct.unpack_from(">i", data, offset)[0]


def _write_int(value: int) -> bytes:
    """Pack a big-endian 4-byte signed integer."""
    return struct.pack(">i", value)


def _index_of(data: bytes, pattern: bytes, start: int = 0, end: Optional[int] = None) -> int:
    """Return the first index of *pattern* in *data[start:end]*, or -1."""
    if end is None:
        end = len(data)
    idx = data.find(pattern, start, end)
    return idx  # bytes.find already returns -1 when not found


def _read_utf16be(data: bytes, offset: int, length: int) -> str:
    try:
        return data[offset : offset + length].decode("utf-16-be")
    except Exception:
        return ""


def _to_utf16be(s: str) -> bytes:
    return s.encode("utf-16-be")


def _nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


# ---------------------------------------------------------------------------
# Session entry
# ---------------------------------------------------------------------------

class _SessionEntry:
    __slots__ = ("offset", "length", "filepath", "raw_entry")

    def __init__(self) -> None:
        self.offset: int = 0
        self.length: int = 0
        self.filepath: str = ""
        self.raw_entry: bytes = b""


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------

class _SessionParser:
    """Reads and writes a single .session binary file."""

    def __init__(self) -> None:
        self._raw: bytes = b""
        self._entries: list[_SessionEntry] = []

    # ------------------------------------------------------------------
    @classmethod
    def read_from(cls, path: Path) -> "_SessionParser":
        obj = cls()
        data = path.read_bytes()
        obj._raw = data

        if len(data) < 8 or data[:4] != _VRSN:
            raise ValueError(f"Invalid session file (missing vrsn): {path.name}")

        # Parse all oent entries
        pos = 0
        while pos < len(data) - 4:
            idx = _index_of(data, _OENT, pos)
            if idx < 0:
                break
            entry = cls._parse_entry(data, idx)
            if entry is not None:
                obj._entries.append(entry)
            pos = idx + 4

        return obj

    # ------------------------------------------------------------------
    @staticmethod
    def _parse_entry(data: bytes, oent_offset: int) -> Optional[_SessionEntry]:
        try:
            if oent_offset + 8 > len(data):
                return None

            entry = _SessionEntry()
            entry.offset = oent_offset
            entry.length = _read_int(data, oent_offset + 4)
            entry_end = min(oent_offset + 8 + entry.length, len(data))
            entry.raw_entry = data[oent_offset:entry_end]

            # Find adat sub-block
            adat_offset = _index_of(data, _ADAT, oent_offset, entry_end)
            if adat_offset < 0:
                return entry

            adat_len = _read_int(data, adat_offset + 4)
            field_pos = adat_offset + 8
            field_end = min(adat_offset + 8 + adat_len, entry_end)

            while field_pos < field_end - 8:
                field_id = _read_int(data, field_pos)
                field_len = _read_int(data, field_pos + 4)
                field_pos += 8

                if field_len < 0 or field_len > 4096 or field_pos + field_len > field_end:
                    break

                if field_id == _FIELD_FILEPATH:
                    entry.filepath = _read_utf16be(data, field_pos, field_len)

                field_pos += field_len

            return entry
        except Exception:
            return None

    # ------------------------------------------------------------------
    def get_unique_paths(self) -> set[str]:
        paths: set[str] = set()
        for e in self._entries:
            if e.filepath:
                paths.add(e.filepath.replace("\x00", ""))
        return paths

    # ------------------------------------------------------------------
    def update_path(self, old_path: str, new_path: str) -> int:
        """Replace *old_path* with *new_path* in raw data; returns replacement count."""
        old_path = old_path.replace("\x00", "")
        new_path = new_path.replace("\x00", "")

        old_bytes = _to_utf16be(old_path)
        count = 0
        pos = 0
        while True:
            idx = _index_of(self._raw, old_bytes, pos)
            if idx < 0:
                break
            count += 1
            pos = idx + len(old_bytes)

        if count > 0:
            self._raw = self._rebuild_with_updated_paths(old_path, new_path)
            for e in self._entries:
                if e.filepath.replace("\x00", "") == old_path:
                    e.filepath = new_path

        return count

    # ------------------------------------------------------------------
    def _rebuild_with_updated_paths(self, old_path: str, new_path: str) -> bytes:
        out = bytearray()
        first_oent = _index_of(self._raw, _OENT, 0)
        if first_oent < 0:
            return self._raw

        out += self._raw[:first_oent]
        pos = first_oent

        while pos < len(self._raw):
            oent_pos = _index_of(self._raw, _OENT, pos)
            if oent_pos < 0:
                out += self._raw[pos:]
                break

            if oent_pos > pos:
                out += self._raw[pos:oent_pos]

            entry_len = _read_int(self._raw, oent_pos + 4)
            entry_end = min(oent_pos + 8 + entry_len, len(self._raw))
            entry_data = self._raw[oent_pos + 8 : entry_end]

            old_bytes = _to_utf16be(old_path)
            if _index_of(entry_data, old_bytes) >= 0:
                new_entry_data = self._rebuild_entry(entry_data, old_path, new_path)
                out += _OENT
                out += _write_int(len(new_entry_data))
                out += new_entry_data
            else:
                out += self._raw[oent_pos:entry_end]

            pos = entry_end

        return bytes(out)

    # ------------------------------------------------------------------
    @staticmethod
    def _rebuild_entry(entry_data: bytes, old_path: str, new_path: str) -> bytes:
        out = bytearray()

        adat_pos = _index_of(entry_data, _ADAT, 0)
        if adat_pos < 0:
            return entry_data

        out += entry_data[:adat_pos]

        adat_len = _read_int(entry_data, adat_pos + 4)
        adat_end = min(adat_pos + 8 + adat_len, len(entry_data))

        adat_out = bytearray()
        field_pos = adat_pos + 8

        while field_pos < adat_end - 8:
            field_id = _read_int(entry_data, field_pos)
            field_len = _read_int(entry_data, field_pos + 4)

            if field_len < 0 or field_len > 4096 or field_pos + 8 + field_len > adat_end:
                break

            if field_id == _FIELD_FILEPATH:
                current_raw = _read_utf16be(entry_data, field_pos + 8, field_len)
                current_clean = current_raw.replace("\x00", "")
                old_clean = old_path.replace("\x00", "")

                if old_clean == current_clean:
                    # Count trailing nulls to preserve them
                    trailing_nulls = 0
                    for ch in reversed(current_raw):
                        if ch == "\x00":
                            trailing_nulls += 1
                        else:
                            break

                    new_clean = new_path.replace("\x00", "")
                    new_with_nulls = new_clean + "\x00" * trailing_nulls
                    new_path_bytes = _to_utf16be(new_with_nulls)

                    adat_out += _write_int(field_id)
                    adat_out += _write_int(len(new_path_bytes))
                    adat_out += new_path_bytes
                    field_pos += 8 + field_len
                    continue

            # Copy field as-is
            adat_out += _write_int(field_id)
            adat_out += _write_int(field_len)
            adat_out += entry_data[field_pos + 8 : field_pos + 8 + field_len]
            field_pos += 8 + field_len

        out += _ADAT
        out += _write_int(len(adat_out))
        out += adat_out

        if adat_end < len(entry_data):
            out += entry_data[adat_end:]

        return bytes(out)

    # ------------------------------------------------------------------
    def write_to(self, path: Path) -> None:
        """Atomic write: write to .tmp then rename."""
        tmp_path = path.with_suffix(path.suffix + ".tmp")
        tmp_path.write_bytes(self._raw)
        tmp_path.replace(path)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def _build_session_index(music_library_paths: list[str], log: Callable) -> dict[str, list[str]]:
    """Scan all music library paths with parallel MediaLibrary and return a
    lowercase-filename → [absolute_path, ...] index — identical strategy to
    Step 2's build_library_index, reused here for consistency and speed.

    Session files store absolute paths, so callers use candidates[0] directly
    (no resolve_serato_path / normalize_path_for_database needed).
    """
    all_tracks: list[str] = []
    for lib_path in music_library_paths:
        if not Path(lib_path).exists():
            log(f"⚠  Library path not found, skipping: {lib_path}")
            continue
        lib = MediaLibrary.read_from(lib_path)
        lib.flatten_tracks(all_tracks)

    log(f"Indexed {len(all_tracks)} tracks for session path lookup.")

    # Delegate to the canonical pipeline helper for a consistent index structure
    from collections import defaultdict
    index: dict[str, list[str]] = defaultdict(list)
    for path in all_tracks:
        index[path.rsplit("/", 1)[-1].lower()].append(path)
    return dict(index)


def scan_broken_paths(
    serato_path: str,
    music_library_paths: list[str],
    dry_run: bool = True,
    log_callback: Optional[Callable[[str], None]] = None,
) -> dict:
    """Scan all .session files for broken paths.

    Uses the same MediaLibrary parallel-scandir strategy as pipeline Step 2
    to build a filename index upfront, then resolves broken paths via O(1)
    dict lookup. Session files store absolute paths, so no database
    normalisation is needed — the library absolute path is used directly.

    Returns:
        {
            "fixable":   {old_path: new_path, ...},
            "unfixable": [path, ...]
        }
    """
    log = log_callback or print

    log("Checking for broken filepaths in session files...")
    if dry_run:
        log("(dry run — no files will be written)")

    sessions_dir = Path(serato_path) / "History" / "Sessions"
    if not sessions_dir.is_dir():
        log(f"✗ Sessions directory not found: {sessions_dir}")
        log("  Make sure you're pointing to the _Serato_ folder in ~/Music/")
        return {"fixable": {}, "unfixable": []}

    session_files = sorted(sessions_dir.glob("*.session"))
    if not session_files:
        log("No session files found.")
        return {"fixable": {}, "unfixable": []}

    log(f"Found {len(session_files)} session files to scan.")

    # Build index once — parallel scandir across all library paths
    lib_index = _build_session_index(music_library_paths, log)

    fixable: dict[str, str] = {}
    unfixable: list[str] = []
    already_checked: set[str] = set()
    total_broken = 0

    for sf in session_files:
        try:
            session = _SessionParser.read_from(sf)
        except Exception as exc:
            log(f"✗ Failed to read session: {sf.name} — {exc}")
            continue

        for track_path in session.get_unique_paths():
            track_path = track_path.replace("\x00", "")
            if track_path in already_checked:
                continue
            already_checked.add(track_path)

            if not Path(track_path).exists():
                total_broken += 1
                key = Path(track_path).name.lower()
                candidates = lib_index.get(key)

                if candidates and len(candidates) == 1 and Path(candidates[0]).exists():
                    # Unique match — use absolute path directly (session files don't use relative paths)
                    fixable[track_path] = candidates[0]
                    log(f"  Found fix for broken path:")
                    log(f"    Broken: {track_path}")
                    log(f"    Fixed:  {candidates[0]}")
                elif candidates and len(candidates) > 1:
                    # Ambiguous — same behaviour as Step 2: skip silently
                    unfixable.append(track_path)
                else:
                    unfixable.append(track_path)

    # ── Write report file ────────────────────────────────────────────────────
    _write_scan_report(serato_path, fixable, unfixable, log)

    log(f"Broken paths found: {total_broken}")
    log(f"  - Fixable:   {len(fixable)}")
    log(f"  - Unfixable: {len(unfixable)}")

    return {"fixable": fixable, "unfixable": unfixable}


def _write_scan_report(
    serato_path: str,
    fixable: dict[str, str],
    unfixable: list[str],
    log: Callable,
) -> None:
    """Write a human-readable scan report to ~/Music/_Serato_/session_scan_report.txt.

    Unfixable paths are split into two buckets:
      - EXISTS_ELSEWHERE: file is on disk but outside your Music Folder (wrong volume, etc.)
      - NOT_FOUND: file cannot be located anywhere — likely deleted
    """
    import datetime

    report_path = Path(serato_path) / "session_scan_report.txt"
    lines: list[str] = []

    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines += [
        f"Session Path Scan Report — {ts}",
        "=" * 72,
        f"Fixable:   {len(fixable)}",
        f"Unfixable: {len(unfixable)}",
        "",
    ]

    if fixable:
        lines += ["── FIXABLE PATHS (" + str(len(fixable)) + ") ──────────────────────────────────────", ""]
        for old, new in sorted(fixable.items()):
            lines += [f"  Broken: {old}", f"  Fixed:  {new}", ""]

    if unfixable:
        exists_elsewhere: list[str] = []
        not_found: list[str] = []
        for p in unfixable:
            if Path(p).exists():
                exists_elsewhere.append(p)
            else:
                not_found.append(p)

        if exists_elsewhere:
            lines += [
                f"── EXISTS BUT OUTSIDE MUSIC FOLDER ({len(exists_elsewhere)}) ──────────────────",
                "  (file is on disk but not in your configured Music Folder)",
                "",
            ]
            for p in sorted(exists_elsewhere):
                lines.append(f"  {p}")
            lines.append("")

        if not_found:
            lines += [
                f"── NOT FOUND ANYWHERE ({len(not_found)}) — likely deleted ──────────────────",
                "",
            ]
            for p in sorted(not_found):
                lines.append(f"  {p}")
            lines.append("")

    try:
        report_path.write_text("\n".join(lines), encoding="utf-8")
        log(f"📄 Report written: {report_path}")
    except Exception as exc:
        log(f"⚠️  Could not write report: {exc}")


def fix_broken_paths(
    serato_path: str,
    music_library_paths: list[str],
    dry_run: bool = False,
    log_callback: Optional[Callable[[str], None]] = None,
) -> tuple[int, int]:
    """Scan and (unless dry_run) rewrite .session files with corrected paths.

    Returns:
        (sessions_fixed, entries_fixed)
    """
    log = log_callback or print

    result = scan_broken_paths(serato_path, music_library_paths, dry_run=dry_run, log_callback=log)
    fixable: dict[str, str] = result["fixable"]

    if not fixable or dry_run:
        if dry_run:
            log("Dry run complete — no files written.")
        else:
            log("No broken paths could be fixed.")
        return (0, 0)

    sessions_dir = Path(serato_path) / "History" / "Sessions"
    session_files = sorted(sessions_dir.glob("*.session"))

    log("")
    log("=== Updating Session Files ===")

    total_sessions = len(session_files)
    sessions_fixed = 0
    entries_fixed = 0

    # Parallel processing with up to 4 workers (mirrors Java implementation)
    import threading
    lock = threading.Lock()

    def _process_session(idx_sf):
        idx, sf = idx_sf
        try:
            session = _SessionParser.read_from(sf)
        except Exception as exc:
            log(f"✗ Failed to read session: {sf.name} — {exc}")
            return 0, 0

        local_entries = 0
        for old, new in fixable.items():
            local_entries += session.update_path(old, new)

        if local_entries > 0:
            try:
                session.write_to(sf)
                log(f"[{idx}/{total_sessions}] Fixed {local_entries} path(s) in: {sf.name}")
                return 1, local_entries
            except Exception as exc:
                log(f"✗ [{idx}/{total_sessions}] Failed to write: {sf.name} — {exc}")

        return 0, 0

    num_workers = min(4, os.cpu_count() or 1)
    with ThreadPoolExecutor(max_workers=num_workers) as pool:
        futures = {pool.submit(_process_session, (i + 1, sf)) for i, sf in enumerate(session_files)}
        for fut in as_completed(futures):
            s, e = fut.result()
            sessions_fixed += s
            entries_fixed += e

    log("")
    if sessions_fixed:
        log(f"Fixed {entries_fixed} path entries across {sessions_fixed} session file(s).")
    else:
        log("No session files required updates.")

    return (sessions_fixed, entries_fixed)
