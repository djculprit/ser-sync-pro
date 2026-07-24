"""
MediaLibrary — recursive filesystem scanner for audio/video tracks.

Mirrors Java's cdd_sync_media_library: parallel scan with ThreadPoolExecutor
(max 4 workers) when >1 subdirectory, sorted children, real-path resolution.
"""

from __future__ import annotations

import logging
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import List, Optional

logger = logging.getLogger("cdd_sync")

MEDIA_EXTENSIONS: frozenset[str] = frozenset({
    ".mp3", ".flac", ".wav", ".ogg", ".aif", ".aiff", ".aac", ".alac", ".m4a",
    ".mov", ".mp4", ".avi", ".flv", ".mpg", ".mpeg", ".dv", ".qtz",
})

_MAX_WORKERS = 4


class MediaLibrary:
    """Tree node representing a directory and its media tracks."""

    def __init__(self, directory: str) -> None:
        self.directory: str = directory
        self.tracks: list[str] = []          # resolved absolute paths, sorted
        self.children: list[MediaLibrary] = []  # sorted alphabetically by directory name

    # ── Recursive counts ─────────────────────────────────────────────────────

    def total_tracks(self) -> int:
        result = len(self.tracks)
        for child in self.children:
            result += child.total_tracks()
        return result

    def total_directories(self) -> int:
        result = len(self.children)
        for child in self.children:
            result += child.total_directories()
        return result

    # ── Flatten / remove ─────────────────────────────────────────────────────

    def flatten_tracks(self, out: Optional[List[str]] = None) -> List[str]:
        """Return all track paths in this subtree as a flat list."""
        if out is None:
            out = []
        out.extend(self.tracks)
        for child in self.children:
            child.flatten_tracks(out)
        return out

    def remove_tracks(self, paths_to_remove: List[str]) -> int:
        """Remove specified paths from this node and all children. Returns count removed."""
        remove_set = set(paths_to_remove)
        before = len(self.tracks)
        self.tracks = [t for t in self.tracks if t not in remove_set]
        removed = before - len(self.tracks)
        for child in self.children:
            removed += child.remove_tracks(paths_to_remove)
        return removed

    # ── Factory ──────────────────────────────────────────────────────────────

    @classmethod
    def read_from(cls, media_library_path: str) -> "MediaLibrary":
        """Scan *media_library_path* recursively and return a populated MediaLibrary tree."""
        root = cls(".")
        root._collect_all(media_library_path)
        return root

    # ── Internal scan ────────────────────────────────────────────────────────

    def _collect_all(self, path: str) -> None:
        try:
            entries = list(os.scandir(path))
        except OSError as exc:
            logger.error("Cannot scan directory %s: %s", path, exc)
            return

        tracks_found: list[str] = []
        subdirs: list[os.DirEntry] = []

        for entry in entries:
            if entry.is_file(follow_symlinks=False):
                if _is_media(entry.name):
                    try:
                        real = str(Path(entry.path).resolve())
                    except OSError:
                        real = os.path.abspath(entry.path)
                    tracks_found.append(real)
            elif entry.is_dir(follow_symlinks=False):
                subdirs.append(entry)

        self.tracks = sorted(tracks_found)

        # Sort subdirs alphabetically (mirrors Java TreeSet<cdd_sync_media_library>)
        subdirs.sort(key=lambda e: e.name)

        if len(subdirs) > 1:
            # Parallel scan
            child_map: dict[str, MediaLibrary] = {}
            with ThreadPoolExecutor(max_workers=_MAX_WORKERS) as pool:
                futures = {
                    pool.submit(_scan_child, entry.name, entry.path): entry.name
                    for entry in subdirs
                }
                for future in as_completed(futures):
                    name = futures[future]
                    try:
                        child_map[name] = future.result()
                    except Exception as exc:
                        logger.error("Error scanning directory %s: %s", name, exc)
            # Reconstruct sorted order
            self.children = [child_map[e.name] for e in subdirs if e.name in child_map]
        else:
            for entry in subdirs:
                child = MediaLibrary(entry.name)
                child._collect_all(entry.path)
                self.children.append(child)

    # ── Dunder ───────────────────────────────────────────────────────────────

    def __repr__(self) -> str:
        return f"MediaLibrary(dir={self.directory!r}, tracks={len(self.tracks)}, children={len(self.children)})"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _is_media(filename: str) -> bool:
    dot = filename.rfind(".")
    return dot >= 0 and filename[dot:].lower() in MEDIA_EXTENSIONS


def _scan_child(name: str, full_path: str) -> MediaLibrary:
    child = MediaLibrary(name)
    child._collect_all(full_path)
    return child
