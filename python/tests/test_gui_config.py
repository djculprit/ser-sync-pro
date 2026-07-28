"""
Tests for gui.config_io — the Flet-control <-> SyncConfig bridge.

Controls are plain Flet data objects (no running page/event loop needed),
so _build_config / _load_config are testable as ordinary functions.
"""
import flet as ft
import pytest

from gui.config_io import _ConfigControls, _build_config, _load_config


def _controls(**overrides) -> _ConfigControls:
    defaults = dict(
        music_field=ft.TextField(value="/Volumes/Drive/Music"),
        serato_field=ft.TextField(value="/Volumes/Drive/_Serato_"),
        parent_field=ft.TextField(value=""),
        cb_backup=ft.Checkbox(value=True),
        cb_sort=ft.Checkbox(value=False),
        cb_step1=ft.Checkbox(value=True),
        cb_step2=ft.Checkbox(value=True),
        cb_step3=ft.Checkbox(value=True),
        cb_step4=ft.Checkbox(value=True),
        cb_dupe_scan=ft.Checkbox(value=False),
        dd_detection=ft.Dropdown(value="name-and-size"),
        dd_move=ft.Dropdown(value="false"),
        cb_dry_run=ft.Checkbox(value=False),
    )
    defaults.update(overrides)
    return _ConfigControls(**defaults)


class TestBuildConfig:
    def test_strips_whitespace_from_paths(self):
        cfg = _build_config(_controls(
            music_field=ft.TextField(value="  /Music  "),
            serato_field=ft.TextField(value="  /Serato  "),
        ))
        assert cfg.music_library_path == "/Music"
        assert cfg.serato_library_path == "/Serato"

    def test_blank_parent_crate_becomes_none(self):
        cfg = _build_config(_controls(parent_field=ft.TextField(value="   ")))
        assert cfg.parent_crate_path is None

    def test_non_blank_parent_crate_is_stripped(self):
        cfg = _build_config(_controls(parent_field=ft.TextField(value="  Current  ")))
        assert cfg.parent_crate_path == "Current"

    def test_checkbox_values_map_to_config_fields(self):
        cfg = _build_config(_controls(
            cb_backup=ft.Checkbox(value=False),
            cb_step2=ft.Checkbox(value=False),
        ))
        assert cfg.backup_enabled is False
        assert cfg.step2_enabled is False
        assert cfg.step1_enabled is True  # untouched control stays default

    def test_dry_run_defaults_false_when_control_absent(self):
        cfg = _build_config(_controls(cb_dry_run=None))
        assert cfg.dry_run is False

    def test_dry_run_reflects_checkbox(self):
        cfg = _build_config(_controls(cb_dry_run=ft.Checkbox(value=True)))
        assert cfg.dry_run is True

    def test_dropdown_none_values_fall_back_to_defaults(self):
        cfg = _build_config(_controls(
            dd_detection=ft.Dropdown(value=None),
            dd_move=ft.Dropdown(value=None),
        ))
        assert cfg.dupe_detection_mode == "off"
        assert cfg.dupe_move_mode == "false"


def _blank_controls() -> _ConfigControls:
    return _ConfigControls(
        music_field=ft.TextField(value=""),
        serato_field=ft.TextField(value=""),
        parent_field=ft.TextField(value=""),
        cb_backup=ft.Checkbox(value=False),
        cb_sort=ft.Checkbox(value=False),
        cb_step1=ft.Checkbox(value=False),
        cb_step2=ft.Checkbox(value=False),
        cb_step3=ft.Checkbox(value=False),
        cb_step4=ft.Checkbox(value=False),
        cb_dupe_scan=ft.Checkbox(value=False),
        dd_detection=ft.Dropdown(value=""),
        dd_move=ft.Dropdown(value=""),
        cb_dry_run=ft.Checkbox(value=False),
    )


class TestLoadConfig:
    def test_populates_controls_from_existing_file(self, tmp_path):
        from config import SyncConfig
        cfg = SyncConfig(
            music_library_path="/Music",
            serato_library_path="/Serato",
            parent_crate_path="Current",
            backup_enabled=False,
            step2_enabled=False,
            dupe_scan_enabled=True,
            dupe_detection_mode="name-only",
            dry_run=True,
        )
        cfg_path = tmp_path / "config.yaml"
        cfg.save(cfg_path)

        controls = _blank_controls()
        _load_config(controls, cfg_path=cfg_path)

        assert controls.music_field.value == "/Music"
        assert controls.serato_field.value == "/Serato"
        assert controls.parent_field.value == "Current"
        assert controls.cb_backup.value is False
        assert controls.cb_step2.value is False
        assert controls.cb_dupe_scan.value is True
        assert controls.dd_detection.value == "name-only"
        assert controls.cb_dry_run.value is True

    def test_silent_noop_when_file_missing(self, tmp_path):
        controls = _blank_controls()
        _load_config(controls, cfg_path=tmp_path / "does_not_exist.yaml")
        assert controls.music_field.value == ""

    def test_silent_noop_on_malformed_yaml(self, tmp_path):
        bad_path = tmp_path / "config.yaml"
        bad_path.write_text("music_library_path: [unterminated")
        controls = _blank_controls()
        _load_config(controls, cfg_path=bad_path)  # must not raise
        assert controls.music_field.value == ""

    def test_dry_run_control_absent_is_skipped_without_error(self, tmp_path):
        from config import SyncConfig
        cfg = SyncConfig(music_library_path="/Music", serato_library_path="/Serato", dry_run=True)
        cfg_path = tmp_path / "config.yaml"
        cfg.save(cfg_path)

        controls = _blank_controls()
        controls.cb_dry_run = None
        _load_config(controls, cfg_path=cfg_path)  # must not raise
        assert controls.music_field.value == "/Music"
