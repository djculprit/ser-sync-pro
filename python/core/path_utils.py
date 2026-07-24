"""
Path normalization utilities.
Ports: cdd_sync_binary_utils.normalizePath() and normalizePathForDatabase()

CRITICAL: Serato stores paths in NFD (decomposed) Unicode.
macOS filesystem returns NFC (precomposed).
These two functions must be used consistently:
  - normalize_path()             → for COMPARISON only (NFC + lowercase)
  - normalize_path_for_database()→ for WRITING to crate/database (case-preserved, strip volume prefix)
"""

import unicodedata
import re


# Matches /Volumes/DriveName/ or /DriveName/ prefixes
# IGNORECASE required: normalize_path() lowercases before stripping, so the regex
# must match /volumes/... — mirrors Java's lowercase replaceAll("^/volumes/[^/]+/", "")
_VOLUME_PREFIX_RE = re.compile(r'^/volumes/[^/]+/', re.IGNORECASE)
_ROOT_PREFIX_RE = re.compile(r'^/[^/]+/')


def normalize_path(path: str) -> str:
    """
    NFC + lowercase. Strips leading volume/drive prefix.
    Use for COMPARISON only — never write this form to disk.
    Ports: cdd_sync_binary_utils.normalizePath()
    """
    path = unicodedata.normalize('NFC', path).lower()
    path = _VOLUME_PREFIX_RE.sub('', path)
    return path


def normalize_path_for_database(path: str) -> str:
    """
    Case-preserving. Strips leading volume/drive prefix.
    Use when WRITING paths to .crate files or database V2.
    Ports: cdd_sync_binary_utils.normalizePathForDatabase()
    """
    path = _VOLUME_PREFIX_RE.sub('', path)
    if path.startswith('/'):
        path = _ROOT_PREFIX_RE.sub('', path)
    return path


def normalize_for_dedup(path: str) -> str:
    """
    Filename-leaf + NFC + lowercase. For addTrack() dedup key.
    Ports: cdd_sync_crate.normalizeForDedup()
    """
    # Extract filename from path (handles both / and \)
    last_slash = max(path.rfind('/'), path.rfind('\\'))
    filename = path[last_slash + 1:] if last_slash >= 0 else path
    return unicodedata.normalize('NFC', filename.lower())


def resolve_serato_path(track_path: str, database) -> str:
    """
    If the database has an exact-encoding record for this filename, return that.
    Otherwise return normalize_path_for_database(track_path).
    Ports: cdd_sync_binary_utils.resolveSeratoPath()
    """
    if database is not None:
        db_path = database.get_original_path_by_filename(track_path)
        if db_path is not None:
            return db_path
    return normalize_path_for_database(track_path)
