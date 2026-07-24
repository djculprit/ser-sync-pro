"""
DatabaseFixer — patches broken file paths in Serato's binary 'database V2' file.

Algorithm (O(DbSize + N)):
  1. Pre-encode all (old_path → new_path) into a bytes → bytes lookup dict.  O(N)
  2. Structural TLV walk — one pass over the file, zero unnecessary copies:
       • Jump past top-level non-otrk tags in one stride.
       • Inside each otrk block, jump past non-pfil inner tags in one stride.
       • For each pfil: dict-lookup the payload (O(1)) to check for a match.
       • Record hits; no modifications during the scan.
  3. Sort hits by payload_start descending. Apply in reverse: each bytearray
     splice only shifts data above the current cursor, so all earlier offsets
     remain valid. Update pfil + parent otrk length fields in-place.
  4. Write the modified database once.

Key implementation decisions vs. the previous version:
  • struct.unpack_from works directly on bytearray — no bytes() copy per tag.
  • bytearray[a:b] == b"tag" works natively — no bytes() copy per comparison.
  • path.write_bytes(bytearray) works natively — no bytes() copy on write.
  • All three previously copied the ENTIRE file on every tag iteration.
"""

from __future__ import annotations

import logging
import os
import struct
import threading
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

from core.binary_utils import encode_utf16be, read_file

logger = logging.getLogger("cdd_sync")

_OTRK = b"otrk"
_PFIL = b"pfil"
_HDR = 8  # 4-byte tag + 4-byte big-endian length


# ---------------------------------------------------------------------------
# Inline helpers — avoid function-call + copy overhead in tight inner loop
# ---------------------------------------------------------------------------

def _u32(data: bytearray, offset: int) -> int:
    """Read big-endian uint32 directly from bytearray. Zero allocations."""
    return struct.unpack_from(">I", data, offset)[0]


def _w32(data: bytearray, offset: int, value: int) -> None:
    """Write big-endian uint32 directly into bytearray. Zero allocations."""
    struct.pack_into(">I", data, offset, value)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def update_paths(
    database_path: str,
    path_fixes: Dict[str, str],
    cancel_event: Optional[threading.Event] = None,
    log_callback: Optional[Callable[[str], None]] = None,
    progress_interval: int = 100,
) -> int:
    """
    Patch broken pfil paths in Serato's binary 'database V2'.

    Complexity: O(DbSize + N) — one structural walk, one write.

    Returns the number of paths successfully patched, or 0 on I/O error.
    """
    db_path = Path(database_path)
    if not db_path.exists():
        logger.error("database V2 not found: %s", database_path)
        return 0

    try:
        data = bytearray(read_file(db_path))
    except IOError as exc:
        logger.error("Error reading database V2: %s", exc)
        return 0

    # Step A — pre-encode all fix pairs into a bytes→bytes dict. O(N)
    fixes: Dict[bytes, bytes] = {
        encode_utf16be(old): encode_utf16be(new)
        for old, new in path_fixes.items()
    }

    # Step B — single structural TLV walk; collect all hits. O(DbSize)
    hits = _scan_pfil_hits(data, fixes)
    if not hits:
        return 0

    # Step C — apply hits highest-offset-first; earlier offsets stay valid.
    hits.sort(key=lambda h: h[1], reverse=True)

    applied = 0
    total = len(hits)
    for pfil_len_off, payload_start, old_len, new_payload, otrk_start in hits:
        if cancel_event and cancel_event.is_set():
            logger.info("database_fixer: cancelled after %d/%d patches", applied, total)
            if log_callback:
                log_callback(f"[CANCELLED] Step 1: stopped after {applied}/{total} paths patched.")
            break

        new_len = len(new_payload)
        diff = new_len - old_len

        # Splice — bytearray handles the memory shift natively (C-level memmove)
        data[payload_start: payload_start + old_len] = new_payload

        # Update pfil length (before splice point — unaffected by above splice)
        _w32(data, pfil_len_off, new_len)

        # Update parent otrk length (also before splice point)
        if otrk_start != -1 and diff != 0:
            _w32(data, otrk_start + 4, _u32(data, otrk_start + 4) + diff)

        applied += 1
        if log_callback and progress_interval > 0 and applied % progress_interval == 0:
            msg = f"Step 1: patching… {applied}/{total} fixed"
            logger.info(msg)
            log_callback(msg)

    if applied > 0:
        # Atomic write: write to a sibling .tmp then rename.
        # os.replace() is a single syscall on POSIX — the original file is
        # never touched until the new data is fully flushed to disk.
        # This prevents a corrupt database V2 if the process is killed mid-write.
        tmp_path = db_path.with_suffix(".tmp")
        try:
            tmp_path.write_bytes(data)
            os.replace(tmp_path, db_path)
        except IOError as exc:
            logger.error("Error writing database V2: %s", exc)
            try:
                tmp_path.unlink(missing_ok=True)
            except OSError:
                pass
            return 0

    return applied


# ---------------------------------------------------------------------------
# Internal — structural TLV walk
# ---------------------------------------------------------------------------

def _scan_pfil_hits(
    data: bytearray,
    fixes: Dict[bytes, bytes],
) -> List[Tuple[int, int, int, bytes, int]]:
    """
    Walk the database V2 TLV structure in one pass. Zero unnecessary copies.

    Returns a list of hits:
        (pfil_len_field_offset, payload_start, old_len, new_payload, otrk_start)
    """
    hits: List[Tuple[int, int, int, bytes, int]] = []
    end = len(data)
    pos = 0

    while pos + _HDR <= end:
        # Read tag + length directly — no bytes() copy
        tag_len = _u32(data, pos + 4)
        body_start = pos + _HDR
        body_end = body_start + tag_len

        if body_end > end:
            break  # malformed top-level record

        if data[pos: pos + 4] == _OTRK:
            otrk_start = pos
            inner = body_start

            while inner + _HDR <= body_end:
                inner_len = _u32(data, inner + 4)
                inner_body_start = inner + _HDR
                inner_body_end = inner_body_start + inner_len

                if inner_body_end > body_end:
                    break  # malformed inner record

                if data[inner: inner + 4] == _PFIL:
                    # bytes() here is unavoidable — dict keys must be hashable.
                    # The slice is only as large as the path (~200–400 bytes),
                    # not the whole file.
                    payload = bytes(data[inner_body_start: inner_body_end])
                    if payload in fixes:
                        hits.append((
                            inner + 4,          # pfil length field offset
                            inner_body_start,   # payload start
                            inner_len,          # old payload length
                            fixes[payload],     # replacement bytes
                            otrk_start,         # for parent otrk length patch
                        ))

                inner = inner_body_end  # stride to next inner tag

            pos = body_end  # stride past entire otrk

        else:
            pos = body_end  # stride past unknown top-level tag

    return hits


# ---------------------------------------------------------------------------
# Legacy shim — retained for any callers that imported this directly
# ---------------------------------------------------------------------------

def _index_otrk_blocks(data: bytearray) -> List[Tuple[int, int]]:
    """Retained for backward compatibility. Not used internally."""
    blocks: List[Tuple[int, int]] = []
    pos = 0
    end = len(data)
    while pos + _HDR <= end:
        if data[pos: pos + 4] == _OTRK:
            length = _u32(data, pos + 4)
            block_end = pos + _HDR + length
            blocks.append((pos, block_end))
            pos = block_end
        else:
            pos += 1
    return blocks
