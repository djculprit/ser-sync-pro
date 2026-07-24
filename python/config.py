"""
SyncConfig — Configuration dataclass for cdd-sync-pro.

Replaces Java's .properties format with YAML.
Load: SyncConfig.load(path)
Save: config.save(path)
"""

from __future__ import annotations

from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional

import yaml


# ---------------------------------------------------------------------------
# Dupe move mode constants — named by what we KEEP (mirrors Java)
# ---------------------------------------------------------------------------
DUPE_MOVE_KEEP_NEWEST = "keep-newest"   # moves older files
DUPE_MOVE_KEEP_OLDEST = "keep-oldest"  # moves newer files
DUPE_MOVE_OFF = "false"


@dataclass
class SyncConfig:
    # ── Required paths ──────────────────────────────────────────────────────
    music_library_path: str
    serato_library_path: str

    # ── Parent crate ────────────────────────────────────────────────────────
    parent_crate_path: Optional[str] = None

    # ── Sync options ────────────────────────────────────────────────────────
    clear_library_before_sync: bool = False

    # ── Backup ──────────────────────────────────────────────────────────────
    backup_enabled: bool = True

    # ── Deduplication ───────────────────────────────────────────────────────
    dupe_scan_enabled: bool = False
    dupe_move_mode: str = DUPE_MOVE_OFF       # "keep-newest" | "keep-oldest" | "false"
    dupe_detection_mode: str = "off"          # "name-and-size" | "name-only" | "off"

    # ── Crate sorting ───────────────────────────────────────────────────────
    crate_sorting_enabled: bool = False

    # ── Pipeline step toggles (all default True, matching Java) ─────────────
    step0_enabled: bool = True
    step1_enabled: bool = True
    step2_enabled: bool = True
    step3_enabled: bool = True
    step4_enabled: bool = True

    # ── Misc ────────────────────────────────────────────────────────────────
    dry_run: bool = False

    # ── Post-init validation ─────────────────────────────────────────────────
    def __post_init__(self) -> None:
        if not self.music_library_path or not self.music_library_path.strip():
            raise ValueError("Required config option missing: music_library_path")
        if not self.serato_library_path or not self.serato_library_path.strip():
            raise ValueError("Required config option missing: serato_library_path")
        if self.parent_crate_path is not None:
            val = self.parent_crate_path.strip()
            if "%%" in val:
                raise ValueError(
                    "Invalid parent_crate_path: nested paths are not supported. "
                    "Use a single crate name like 'Current', not 'Current%%2025'."
                )
            self.parent_crate_path = val if val else None

    # ── Computed properties ──────────────────────────────────────────────────
    @property
    def dupe_move_enabled(self) -> bool:
        """True when dupe_move_mode is keep-newest or keep-oldest."""
        return self.dupe_move_mode in (DUPE_MOVE_KEEP_NEWEST, DUPE_MOVE_KEEP_OLDEST)

    # ── Factory ──────────────────────────────────────────────────────────────
    @classmethod
    def load(cls, path: Path | str) -> "SyncConfig":
        """Load config from a YAML file."""
        path = Path(path)
        with path.open("r", encoding="utf-8") as fh:
            data: dict = yaml.safe_load(fh) or {}

        # Normalise backwards-compat aliases for dupe_move_mode
        raw_move = str(data.get("dupe_move_mode", DUPE_MOVE_OFF)).strip().lower()
        if raw_move in ("true", "oldest"):
            raw_move = DUPE_MOVE_KEEP_NEWEST
        elif raw_move == "newest":
            raw_move = DUPE_MOVE_KEEP_OLDEST
        data["dupe_move_mode"] = raw_move

        # Required fields
        music = data.get("music_library_path")
        serato = data.get("serato_library_path")
        if not music:
            raise ValueError("Required config option missing: music_library_path")
        if not serato:
            raise ValueError("Required config option missing: serato_library_path")

        return cls(
            music_library_path=str(music).strip(),
            serato_library_path=str(serato).strip(),
            parent_crate_path=data.get("parent_crate_path"),
            clear_library_before_sync=bool(data.get("clear_library_before_sync", False)),
            backup_enabled=bool(data.get("backup_enabled", True)),
            dupe_scan_enabled=bool(data.get("dupe_scan_enabled", False)),
            dupe_move_mode=raw_move,
            dupe_detection_mode=str(data.get("dupe_detection_mode", "off")).strip().lower(),
            crate_sorting_enabled=bool(data.get("crate_sorting_enabled", False)),
            step0_enabled=bool(data.get("step0_enabled", True)),
            step1_enabled=bool(data.get("step1_enabled", True)),
            step2_enabled=bool(data.get("step2_enabled", True)),
            step3_enabled=bool(data.get("step3_enabled", True)),
            step4_enabled=bool(data.get("step4_enabled", True)),
            dry_run=bool(data.get("dry_run", False)),
        )

    # ── Persister ────────────────────────────────────────────────────────────
    def save(self, path: Path | str) -> None:
        """Persist config to a YAML file."""
        path = Path(path)
        data = {
            "music_library_path": self.music_library_path,
            "serato_library_path": self.serato_library_path,
            "parent_crate_path": self.parent_crate_path,
            "clear_library_before_sync": self.clear_library_before_sync,
            "backup_enabled": self.backup_enabled,
            "dupe_scan_enabled": self.dupe_scan_enabled,
            "dupe_move_mode": self.dupe_move_mode,
            "dupe_detection_mode": self.dupe_detection_mode,
            "crate_sorting_enabled": self.crate_sorting_enabled,
            "step0_enabled": self.step0_enabled,
            "step1_enabled": self.step1_enabled,
            "step2_enabled": self.step2_enabled,
            "step3_enabled": self.step3_enabled,
            "step4_enabled": self.step4_enabled,
            "dry_run": self.dry_run,
        }
        with path.open("w", encoding="utf-8") as fh:
            yaml.safe_dump(data, fh, default_flow_style=False, allow_unicode=True)
