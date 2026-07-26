"""
Per-session file logging, shared by the sync pipeline and session fixer.

Every write-capable entry point (run_sync, session_fixer's scan/fix) opens
one of these for its duration so a record of the run survives after the GUI
console is cleared or a double-clicked .command window closes.

Log location mirrors the original Java tool (cdd_sync_main.java): logs live
at <parent of _Serato_>/cdd-sync-pro/logs/, i.e. alongside the volume that
_Serato_ lives on — not relative to wherever the app happens to be launched
from. This matters because a given Serato library normally lives on an
external drive; keeping logs on that same volume means the history travels
with the drive, and multiple drives each keep independent log histories.
"""

from __future__ import annotations

import datetime
import logging
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

_LOGGER_NAME = "cdd_sync"


def _log_dir_for(serato_path: str) -> Path:
    """<parent of _Serato_>/cdd-sync-pro/logs — same layout as the Java tool."""
    return Path(serato_path).resolve().parent / "cdd-sync-pro" / "logs"


@contextmanager
def session_log_file(name: str, serato_path: str) -> Iterator[Path]:
    """Attach a FileHandler to the shared 'cdd_sync' logger for the duration
    of the block, writing to <serato_path's volume>/cdd-sync-pro/logs/
    <name>-<timestamp>.log. Yields the path.

    Every module in sync/ already logs through logging.getLogger("cdd_sync"),
    so this captures a full session record with no changes needed at the
    call sites — it just needs to wrap each public entry point once.
    """
    log_dir = _log_dir_for(serato_path)
    log_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = log_dir / f"{name}-{timestamp}.log"

    handler = logging.FileHandler(log_path, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S"))

    logger = logging.getLogger(_LOGGER_NAME)
    prev_level = logger.level
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)  # ensure info-level pipeline logs reach the file even under the GUI (no basicConfig)

    try:
        yield log_path
    finally:
        logger.removeHandler(handler)
        logger.setLevel(prev_level)
        handler.close()
