"""
cdd-sync-pro Flet GUI — config bundle, build, and load.

Bridges GUI controls (Flet widgets) and SyncConfig. Pure functions with no
Flet event-loop or closure dependencies — safe to unit test directly.
"""

from __future__ import annotations

import dataclasses
from pathlib import Path

import flet as ft


@dataclasses.dataclass
class _ConfigControls:
    """Bundle of GUI controls that map to SyncConfig fields."""

    music_field: ft.TextField
    serato_field: ft.TextField
    parent_field: ft.TextField
    cb_backup: ft.Checkbox
    cb_sort: ft.Checkbox
    cb_step1: ft.Checkbox
    cb_step2: ft.Checkbox
    cb_step3: ft.Checkbox
    cb_step4: ft.Checkbox
    cb_dupe_scan: ft.Checkbox
    dd_detection: ft.Dropdown
    dd_move: ft.Dropdown
    cb_dry_run: ft.Checkbox | None = None


def _build_config(controls: _ConfigControls):
    """Construct a SyncConfig from current GUI control values."""
    from config import SyncConfig
    c = controls
    return SyncConfig(
        music_library_path=(c.music_field.value or "").strip(),
        serato_library_path=(c.serato_field.value or "").strip(),
        parent_crate_path=(c.parent_field.value or "").strip() or None,
        backup_enabled=bool(c.cb_backup.value),
        clear_library_before_sync=False,
        crate_sorting_enabled=bool(c.cb_sort.value),
        step1_enabled=bool(c.cb_step1.value),
        step2_enabled=bool(c.cb_step2.value),
        step3_enabled=bool(c.cb_step3.value),
        step4_enabled=bool(c.cb_step4.value),
        dupe_scan_enabled=bool(c.cb_dupe_scan.value),
        dupe_detection_mode=c.dd_detection.value or "off",
        dupe_move_mode=c.dd_move.value or "false",
        dry_run=bool(c.cb_dry_run.value) if c.cb_dry_run is not None else False,
    )


def _load_config(controls: _ConfigControls, cfg_path: Path | None = None) -> None:
    """Populate controls from config.yaml if it exists. Silent on missing/invalid."""
    try:
        from config import SyncConfig
        c = controls
        if cfg_path is None:
            cfg_path = Path(__file__).parent.parent / "config.yaml"
        if not cfg_path.exists():
            return
        cfg = SyncConfig.load(cfg_path)
        c.music_field.value = cfg.music_library_path or ""
        c.serato_field.value = cfg.serato_library_path or ""
        c.parent_field.value = cfg.parent_crate_path or ""
        c.cb_backup.value = cfg.backup_enabled
        c.cb_sort.value = cfg.crate_sorting_enabled
        c.cb_step1.value = cfg.step1_enabled
        c.cb_step2.value = cfg.step2_enabled
        c.cb_step3.value = cfg.step3_enabled
        c.cb_step4.value = cfg.step4_enabled
        c.cb_dupe_scan.value = cfg.dupe_scan_enabled
        c.dd_detection.value = cfg.dupe_detection_mode
        c.dd_move.value = cfg.dupe_move_mode
        if c.cb_dry_run is not None:
            c.cb_dry_run.value = getattr(cfg, "dry_run", False)
    except Exception:
        pass  # Missing or malformed config — start with defaults
