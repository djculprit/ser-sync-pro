"""
Backup — creates a timestamped copy of the _Serato_ folder.

Mirrors Java's cdd_sync_backup.createBackup():
  - Destination: <volume_root>/cdd-sync-pro/backup/<epoch_ms>_Serato_
  - Uses shutil.copytree with copy2 (preserves metadata)
  - Returns absolute backup path on success, None on failure
"""

from __future__ import annotations

import logging
import shutil
import time
from pathlib import Path
from typing import Optional

from core.binary_utils import format_size

logger = logging.getLogger("cdd_sync")

_BACKUP_FOLDER = "cdd-sync-pro/backup"


def create_backup(serato_path: str) -> Optional[str]:
    """
    Copy *serato_path* (_Serato_ folder) into a timestamped sibling backup directory.

    Returns the absolute path of the backup directory, or None on failure.
    """
    serato_dir = Path(serato_path)

    if not serato_dir.exists() or not serato_dir.is_dir():
        logger.error("Serato folder does not exist: %s", serato_path)
        return None

    parent_dir = serato_dir.parent
    backup_root = parent_dir / _BACKUP_FOLDER

    timestamp_ms = int(time.time() * 1000)
    backup_name = f"{timestamp_ms}_Serato_"
    backup_dir = backup_root / backup_name

    logger.info("Creating backup: %s", backup_dir)

    try:
        shutil.copytree(str(serato_dir), str(backup_dir), copy_function=shutil.copy2)

        total_bytes = _dir_size(backup_dir)
        size_str = format_size(total_bytes)
        logger.info("Backup complete (%s)", size_str)

        return str(backup_dir.resolve())

    except Exception as exc:
        logger.error("Backup failed: %s", exc)
        return None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _dir_size(path: Path) -> int:
    """Return total size in bytes of all files under *path*."""
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())
