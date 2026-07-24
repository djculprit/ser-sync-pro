"""
PrefSorter — manages Serato crate order in neworder.pref.

Mirrors Java's cdd_sync_pref_sorter.sort():
  - Scans top-level Subcrates/ for .crate files
  - Deletes and recreates neworder.pref in UTF-16BE (no BOM)
  - Format: [begin record]\n[crate]<name>\n...[end record]\n
  - No-op if Subcrates missing or empty
"""

from __future__ import annotations

import logging
from pathlib import Path

logger = logging.getLogger("cdd_sync")

_PREF_FILE = "neworder.pref"
_SUBCRATES_DIR = "Subcrates"
_CRATE_EXT = ".crate"
_BEGIN = "[begin record]"
_END = "[end record]"
_CRATE_MARKER = "[crate]"


def sort_crates(serato_path: str) -> None:
    """
    Delete and recreate *neworder.pref* with alphabetically sorted top-level crates.

    *serato_path* is the path to the _Serato_ folder.
    """
    serato_dir = Path(serato_path)
    pref_file = serato_dir / _PREF_FILE

    # Delete existing file if present
    if pref_file.exists():
        try:
            pref_file.unlink()
            logger.info("Deleted existing '%s' for recreation.", _PREF_FILE)
        except OSError as exc:
            logger.error("Failed to delete existing '%s'. Skipping crate sorting. — %s", _PREF_FILE, exc)
            return

    crate_names = _scan_subcrates(serato_dir)

    if not crate_names:
        logger.info("No crates found in '%s'. Skipping.", _SUBCRATES_DIR)
        return

    logger.info("Creating '%s' with %d crates...", _PREF_FILE, len(crate_names))
    crate_names.sort()

    try:
        with pref_file.open("w", encoding="utf-16-be") as fh:
            fh.write(_BEGIN + "\n")
            for name in crate_names:
                fh.write(_CRATE_MARKER + name + "\n")
            fh.write(_END + "\n")
        logger.info(
            "Successfully created '%s' with %d crates sorted alphabetically.",
            _PREF_FILE,
            len(crate_names),
        )
    except OSError as exc:
        logger.error("Failed to create '%s': %s", _PREF_FILE, exc)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _scan_subcrates(serato_dir: Path) -> list[str]:
    subcrates_dir = serato_dir / _SUBCRATES_DIR
    if not subcrates_dir.exists() or not subcrates_dir.is_dir():
        logger.info("Subcrates directory not found: %s", subcrates_dir)
        return []

    names = [
        f.name[: -len(_CRATE_EXT)]
        for f in subcrates_dir.iterdir()
        if f.is_file() and f.name.endswith(_CRATE_EXT)
    ]

    logger.info("Found %d crate files in '%s'.", len(names), _SUBCRATES_DIR)
    return names
