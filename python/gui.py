"""
cdd-sync-pro Flet GUI.
Dark mode, SF Pro Display font, 780×760 window.
"""

from __future__ import annotations

import dataclasses
import queue
import sys
import threading
import time
import tomllib
from pathlib import Path

import flet as ft

# Ensure python/ is on path so config / sync imports work
_HERE = Path(__file__).parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))


def _app_version() -> str:
    """Read the app version from pyproject.toml — the single source of truth."""
    try:
        with (_HERE / "pyproject.toml").open("rb") as fh:
            return tomllib.load(fh)["project"]["version"]
    except (OSError, KeyError, tomllib.TOMLDecodeError):
        return "unknown"


_APP_VERSION = _app_version()


# ── Colour tokens ────────────────────────────────────────────────────────────
_BG          = "#0f1117"  # deep navy-black
_SURFACE     = "#161b22"  # card surface
_BORDER      = "#21262d"  # cool border
_TEXT        = "#8b949e"  # muted body
_LABEL       = "#c9d1d9"  # prominent labels
_ACCENT_GREEN  = "#3fb950"  # success / checked
_ACCENT_AMBER  = "#d29922"  # warning / dry-run
_ACCENT_RED    = "#f85149"  # error / cancel
_ACCENT_BLUE   = "#58a6ff"  # primary / focus
_TERMINAL_BG   = "#0a0c10"  # log panel bg
_LOG_ACCENT    = "#6e40c9"  # log panel left border


def _section(
    title: str,
    content: ft.Control,
    accent_color: str | None = None,
    header_action: ft.Control | None = None,
) -> ft.Container:
    """Titled dark section container with optional left-accent border."""
    left_w = 3 if accent_color else 1
    left_c = accent_color or _BORDER
    if header_action is not None:
        title_row: ft.Control = ft.Row(
            [
                ft.Text(title, size=12, color=_LABEL, weight=ft.FontWeight.W_600, expand=True),
                header_action,
            ],
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
    else:
        title_row = ft.Text(title, size=12, color=_LABEL, weight=ft.FontWeight.W_600)
    return ft.Container(
        content=ft.Column(
            [title_row, ft.Divider(height=1, color=_BORDER), content],
            spacing=8,
        ),
        bgcolor=_SURFACE,
        border=ft.Border(
            left=ft.BorderSide(left_w, left_c),
            top=ft.BorderSide(1, _BORDER),
            right=ft.BorderSide(1, _BORDER),
            bottom=ft.BorderSide(1, _BORDER),
        ),
        border_radius=8,
        padding=ft.Padding(left=12, top=10, right=12, bottom=10),
    )


def _path_row(
    label: str,
    field: ft.TextField,
    on_browse=None,
) -> ft.Row:
    """Label + text field + optional Browse button row."""
    controls: list[ft.Control] = [
        ft.Text(label, width=120, color=_LABEL, size=12),
        field,
    ]
    if on_browse is not None:
        controls.append(
            ft.FilledButton(
                "Browse",
                height=36,
                style=ft.ButtonStyle(
                    bgcolor={"": "#454a4a"},
                    color={"": _TEXT},
                    shape={"": ft.RoundedRectangleBorder(radius=6)},
                ),
                on_click=on_browse,
            )
        )
    return ft.Row(controls, vertical_alignment=ft.CrossAxisAlignment.CENTER)


def _checkbox(label: str, value: bool = True) -> ft.Checkbox:
    return ft.Checkbox(
        label=label,
        value=value,
        label_style=ft.TextStyle(size=12, color=_LABEL),
        fill_color={"selected": _ACCENT_GREEN, "": "#454a4a"},
    )


def _dropdown(options: list[str], value: str) -> ft.Dropdown:
    return ft.Dropdown(
        options=[ft.dropdown.Option(o) for o in options],
        value=value,
        height=36,
        text_size=12,
        content_padding=ft.Padding(left=10, top=4, right=10, bottom=4),
        expand=True,
    )


def _field(hint: str = "", value: str = "") -> ft.TextField:
    return ft.TextField(
        hint_text=hint,
        value=value,
        height=36,
        text_size=12,
        content_padding=ft.Padding(left=10, top=4, right=10, bottom=4),
        border_color=_BORDER,
        focused_border_color=_ACCENT_BLUE,
        cursor_color=_LABEL,
        expand=True,
    )


def _scan_button(ref: ft.Ref, on_click, height: int = 30) -> ft.FilledButton:
    return ft.FilledButton(
        "🔍  Scan",
        ref=ref,
        height=height,
        style=ft.ButtonStyle(
            bgcolor={"": "#1e4a3a", ft.ControlState.HOVERED: "#2a6b52"},
            color={"": "#7dc9ae", ft.ControlState.HOVERED: "#a8e6cf"},
            overlay_color={ft.ControlState.HOVERED: "#00000000"},
            padding={"": ft.Padding(left=10, top=0, right=10, bottom=0)},
            shape={"": ft.RoundedRectangleBorder(radius=6)},
            text_style={"": ft.TextStyle(size=11, weight=ft.FontWeight.W_500)},
        ),
        on_click=on_click,
    )


def _run_button(ref: ft.Ref, on_click, height: int = 30) -> ft.FilledButton:
    return ft.FilledButton(
        "▶  Run",
        ref=ref,
        height=height,
        style=ft.ButtonStyle(
            bgcolor={"": "#2d5f96", ft.ControlState.HOVERED: "#3a78bd"},
            color={"": "#e0eaf5", ft.ControlState.HOVERED: "#ffffff"},
            overlay_color={ft.ControlState.HOVERED: "#00000000"},
            padding={"": ft.Padding(left=12, top=0, right=12, bottom=0)},
            shape={"": ft.RoundedRectangleBorder(radius=6)},
            text_style={"": ft.TextStyle(size=11, weight=ft.FontWeight.W_500)},
        ),
        on_click=on_click,
    )


# ── Main ─────────────────────────────────────────────────────────────────────

async def main(page: ft.Page) -> None:
    # ── Window ───────────────────────────────────────────────────────────────
    page.title = "cdd-sync-pro"
    page.window.width = 780
    page.window.height = 820
    page.window.min_width = 780
    page.window.min_height = 760
    page.window.resizable = True

    # ── Theme ─────────────────────────────────────────────────────────────────
    page.theme_mode = ft.ThemeMode.DARK
    page.theme = ft.Theme(font_family="SF Pro Display")
    page.bgcolor = _BG
    page.padding = ft.Padding(left=20, top=16, right=20, bottom=20)

    # ── File pickers ──────────────────────────────────────────────────────────
    music_picker = ft.FilePicker()
    serato_picker = ft.FilePicker()

    # ── Path fields ─────────────────────────────────────────────────────────
    music_field = _field("/Volumes/YourDrive/Music")
    serato_field = _field("/Volumes/YourDrive/_Serato_")
    parent_field = _field("e.g. Current  (optional)")

    async def _browse_music(_e):
        path = await music_picker.get_directory_path(
            initial_directory=music_field.value or "/"
        )
        if path:
            music_field.value = path
            music_field.update()

    async def _browse_serato(_e):
        path = await serato_picker.get_directory_path(
            initial_directory=serato_field.value or "/"
        )
        if path:
            serato_field.value = path
            serato_field.update()

    # ── Pipeline step checkboxes ─────────────────────────────────────────────
    cb_backup = _checkbox("Backup", value=True)
    cb_step1 = _checkbox("Fix Database Paths", value=True)
    cb_step2 = _checkbox("Fix Crate Paths", value=True)
    cb_step3 = _checkbox("Append Existing Crates", value=True)
    cb_step4 = _checkbox("Create New Crates", value=True)
    cb_sort = _checkbox("Reset Crates A→Z", value=False)
    cb_dry_run = ft.Checkbox(
        label="Dry Run",
        value=False,
        label_style=ft.TextStyle(size=12, color=_ACCENT_AMBER),
        fill_color={"selected": _ACCENT_AMBER, "": "#454a4a"},
    )

    # ── Refs for dynamic controls ─────────────────────────────────────────────
    _log_ref: ft.Ref[ft.ListView] = ft.Ref()
    _status_ref: ft.Ref[ft.Text] = ft.Ref()
    _progress_ref: ft.Ref[ft.ProgressBar] = ft.Ref()
    _start_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _cancel_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _save_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run_backup_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run_sort_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run0_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run1_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run2_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run3_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _run4_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _scan0_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _scan1_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _scan2_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _scan3_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _scan4_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _sf_scan_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _sf_run_ref: ft.Ref[ft.FilledButton] = ft.Ref()
    _cancel_event = threading.Event()
    _update_lock = threading.Lock()  # serialise all page.update() calls across threads

    # Card row style constants
    _CARD_BG = "#1c2030"
    _CARD_BORDER = "#2a3148"

    def _card_step_row(
        cb: ft.Checkbox,
        run_ref: ft.Ref,
        scan_ref: ft.Ref,
        step_n: int,
        step_label: str,
    ) -> ft.Container:
        """Elevated pill card: [checkbox  label──────────  🔍 Scan  ▶ Run]"""
        scan_btn = _scan_button(scan_ref, lambda _e, n=step_n: _run_scan_alone(n))
        run_btn = _run_button(run_ref, lambda _e, n=step_n: _run_step_alone(n))
        cb.label = ""  # label lives as a Text control for layout control
        return ft.Container(
            content=ft.Row(
                [
                    cb,
                    ft.Text(
                        step_label,
                        size=12,
                        color=_LABEL,
                        expand=True,
                    ),
                    scan_btn,
                    run_btn,
                ],
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
                spacing=6,
            ),
            bgcolor=_CARD_BG,
            border=ft.Border(
                left=ft.BorderSide(1, _CARD_BORDER),
                top=ft.BorderSide(1, _CARD_BORDER),
                right=ft.BorderSide(1, _CARD_BORDER),
                bottom=ft.BorderSide(1, _CARD_BORDER),
            ),
            border_radius=8,
            padding=ft.Padding(left=8, top=4, right=8, bottom=4),
        )

    def _scan_run_buttons(scan_ref: ft.Ref, run_ref: ft.Ref, step_n: int) -> ft.Row:
        """Compact [🔍 Scan  ▶ Run] pair for a section header_action slot."""
        scan_btn = _scan_button(scan_ref, lambda _e, n=step_n: _run_scan_alone(n), height=28)
        run_btn = _run_button(run_ref, lambda _e, n=step_n: _run_step_alone(n), height=28)
        return ft.Row([scan_btn, run_btn], spacing=6)

    def _card_flag_row(
        *checkboxes: ft.Checkbox,
    ) -> ft.Container:
        """Compact flat pill for flag checkboxes (no run button)."""
        return ft.Container(
            content=ft.Row(
                list(checkboxes),
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
                spacing=20,
            ),
            bgcolor=_CARD_BG,
            border=ft.Border(
                left=ft.BorderSide(1, _CARD_BORDER),
                top=ft.BorderSide(1, _CARD_BORDER),
                right=ft.BorderSide(1, _CARD_BORDER),
                bottom=ft.BorderSide(1, _CARD_BORDER),
            ),
            border_radius=8,
            padding=ft.Padding(left=8, top=4, right=8, bottom=4),
        )

    def _card_backup_row(cb: ft.Checkbox, ref: ft.Ref) -> ft.Container:
        """Backup row: checkbox + ▶ Run Backup pinned right."""
        run_btn = _run_button(ref, lambda _e: _run_backup_alone())
        cb.label = ""
        return ft.Container(
            content=ft.Row(
                [
                    cb,
                    ft.Text("Backup", size=12, color=_LABEL, expand=True),
                    run_btn,
                ],
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
                spacing=6,
            ),
            bgcolor=_CARD_BG,
            border=ft.Border(
                left=ft.BorderSide(1, _CARD_BORDER),
                top=ft.BorderSide(1, _CARD_BORDER),
                right=ft.BorderSide(1, _CARD_BORDER),
                bottom=ft.BorderSide(1, _CARD_BORDER),
            ),
            border_radius=8,
            padding=ft.Padding(left=8, top=4, right=8, bottom=4),
        )

    def _card_sort_row(cb: ft.Checkbox, ref: ft.Ref) -> ft.Container:
        """Reset Crates A→Z row: checkbox + ▶ Run pinned right."""
        run_btn = _run_button(ref, lambda _e: _run_sort_alone())
        cb.label = ""
        return ft.Container(
            content=ft.Row(
                [
                    cb,
                    ft.Text("Reset Crates A→Z", size=12, color=_LABEL, expand=True),
                    run_btn,
                ],
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
                spacing=6,
            ),
            bgcolor=_CARD_BG,
            border=ft.Border(
                left=ft.BorderSide(1, _CARD_BORDER),
                top=ft.BorderSide(1, _CARD_BORDER),
                right=ft.BorderSide(1, _CARD_BORDER),
                bottom=ft.BorderSide(1, _CARD_BORDER),
            ),
            border_radius=8,
            padding=ft.Padding(left=8, top=4, right=8, bottom=4),
        )

    def _card_session_fixer_row(
        cb: ft.Checkbox,
        scan_ref: ft.Ref,
        run_ref: ft.Ref,
    ) -> ft.Container:
        """Session Fixer pill: [checkbox  label──────────  🔍 Scan  ▶ Run]"""
        scan_btn = _scan_button(scan_ref, lambda _e: _run_session_scan())
        run_btn = _run_button(run_ref, lambda _e: _run_session_fix())
        cb.label = ""
        return ft.Container(
            content=ft.Column(
                [
                    ft.Row(
                        [
                            cb,
                            ft.Text(
                                "Fix broken session paths",
                                size=12,
                                color=_LABEL,
                                expand=True,
                            ),
                            scan_btn,
                            run_btn,
                        ],
                        vertical_alignment=ft.CrossAxisAlignment.CENTER,
                        spacing=6,
                    ),
                    ft.Text(
                        "Scans ~/Music/_Serato_/History/Sessions/*.session — fixes broken paths using your Music Folder",
                        size=11,
                        color=_TEXT,
                        italic=True,
                    ),
                ],
                spacing=4,
            ),
            bgcolor=_CARD_BG,
            border=ft.Border(
                left=ft.BorderSide(1, _CARD_BORDER),
                top=ft.BorderSide(1, _CARD_BORDER),
                right=ft.BorderSide(1, _CARD_BORDER),
                bottom=ft.BorderSide(1, _CARD_BORDER),
            ),
            border_radius=8,
            padding=ft.Padding(left=8, top=4, right=8, bottom=4),
        )

    pipeline_grid = ft.Column(
        [
            _card_backup_row(cb_backup, _run_backup_ref),
            _card_step_row(cb_step1, _run1_ref, _scan1_ref, 1, "Fix Database Paths"),
            _card_step_row(cb_step2, _run2_ref, _scan2_ref, 2, "Fix Crate Paths"),
            _card_step_row(cb_step3, _run3_ref, _scan3_ref, 3, "Append Existing Crates"),
            _card_step_row(cb_step4, _run4_ref, _scan4_ref, 4, "Create New Crates"),
            _card_sort_row(cb_sort, _run_sort_ref),
        ],
        spacing=5,
    )

    # ── Dupe management ───────────────────────────────────────────────────────
    cb_dupe_scan = _checkbox("Scan for duplicates", value=False)
    dd_detection = _dropdown(["name-and-size", "name-only", "off"], "name-and-size")
    dd_move = _dropdown(["keep-oldest", "keep-newest", "false"], "false")
    cb_session_fix = _checkbox("Fix broken session paths", value=False)

    dupe_content = ft.Container(
        content=ft.Column(
            [
                cb_dupe_scan,
                ft.Row(
                    [
                        ft.Text("Detection:", width=80, color=_LABEL, size=12),
                        dd_detection,
                        ft.Text("Move:", width=40, color=_LABEL, size=12),
                        dd_move,
                    ],
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                    spacing=8,
                ),
            ],
            spacing=6,
        ),
        bgcolor=_CARD_BG,
        border=ft.Border(
            left=ft.BorderSide(1, _CARD_BORDER),
            top=ft.BorderSide(1, _CARD_BORDER),
            right=ft.BorderSide(1, _CARD_BORDER),
            bottom=ft.BorderSide(1, _CARD_BORDER),
        ),
        border_radius=8,
        padding=ft.Padding(left=8, top=4, right=8, bottom=4),
    )

    # ── Load config if available; seed defaults if not ────────────────────────
    _cfg_path = Path(__file__).parent / "config.yaml"
    _load_config(
        music_field, serato_field, parent_field,
        cb_backup, cb_sort,
        cb_step1, cb_step2, cb_step3, cb_step4,
        cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
    )
    if not _cfg_path.exists():
        try:
            _build_config(
                music_field, serato_field, parent_field,
                cb_backup, cb_sort,
                cb_step1, cb_step2, cb_step3, cb_step4,
                cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
            ).save(_cfg_path)
        except Exception:
            pass  # Non-fatal — user can still fill paths and click Start

    # ── Assemble layout ───────────────────────────────────────────────────────
    page.add(
        ft.Column(
            [
                # ── Branded header ──────────────────────────────────────────
                ft.Row(
                    [
                        ft.Text(
                            "cdd-sync-pro",
                            size=20,
                            weight=ft.FontWeight.W_700,
                            color=_LABEL,
                        ),
                        ft.Container(
                            content=ft.Text(
                                f"v{_APP_VERSION}",
                                size=10,
                                color=_ACCENT_BLUE,
                                weight=ft.FontWeight.W_600,
                            ),
                            bgcolor="#0d1f3c",
                            border_radius=4,
                            padding=ft.Padding(left=6, top=2, right=6, bottom=2),
                            margin=ft.Margin(left=8, top=0, right=0, bottom=0),
                        ),
                        ft.Container(expand=True),
                        ft.Container(
                            content=ft.Text(
                                "● Live",
                                size=11,
                                color=_ACCENT_GREEN,
                                weight=ft.FontWeight.W_500,
                            ),
                            bgcolor="#0d2218",
                            border_radius=20,
                            padding=ft.Padding(left=10, top=4, right=10, bottom=4),
                            border=ft.Border.all(1, "#1a4a2e"),
                        ),
                    ],
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
                ft.Divider(height=1, color=_BORDER),
                # ── Paths ─────────────────────────────────────────────────────
                _section(
                    "Paths",
                    ft.Column(
                        [
                            _path_row("Music Folder", music_field, _browse_music),
                            _path_row("Serato Path", serato_field, _browse_serato),
                            _path_row("Parent Crate", parent_field),
                        ],
                        spacing=8,
                    ),
                    accent_color=_ACCENT_BLUE,
                ),
                # ── Pipeline / Duplicates / Session Fixer / Log — tabbed ────
                ft.Container(
                    expand=True,
                    content=ft.Tabs(
                        length=2,
                        selected_index=0,
                        content=ft.Column(
                            [
                                ft.TabBar(
                                    tabs=[
                                        ft.Tab(label="Pipeline"),
                                        ft.Tab(label="Log"),
                                    ],
                                    indicator_color=_ACCENT_BLUE,
                                    label_color=_LABEL,
                                    unselected_label_color=_TEXT,
                                ),
                                ft.TabBarView(
                                    expand=True,
                                    controls=[
                                        # ── Pipeline tab ──────────────────────
                                        ft.Column(
                                            [
                                                ft.Container(
                                                    content=ft.Column(
                                                        [
                                                            ft.Text(
                                                                "Pipeline Steps",
                                                                size=12,
                                                                color=_LABEL,
                                                                weight=ft.FontWeight.W_600,
                                                            ),
                                                            ft.Divider(height=1, color=_BORDER),
                                                            pipeline_grid,
                                                        ],
                                                        spacing=8,
                                                    ),
                                                    bgcolor=_SURFACE,
                                                    border=ft.Border(
                                                        left=ft.BorderSide(3, _ACCENT_BLUE),
                                                        top=ft.BorderSide(1, _BORDER),
                                                        right=ft.BorderSide(1, _BORDER),
                                                        bottom=ft.BorderSide(1, _BORDER),
                                                    ),
                                                    border_radius=8,
                                                    padding=ft.Padding(left=12, top=10, right=12, bottom=10),
                                                ),
                                                _section(
                                                    "Dupe Manager", dupe_content,
                                                    accent_color=_ACCENT_AMBER,
                                                    header_action=_scan_run_buttons(_scan0_ref, _run0_ref, 0),
                                                ),
                                                _section(
                                                    "History Session Fixer",
                                                    _card_session_fixer_row(
                                                        cb_session_fix, _sf_scan_ref, _sf_run_ref,
                                                    ),
                                                    accent_color=_ACCENT_AMBER,
                                                ),
                                            ],
                                            spacing=16,
                                            scroll=ft.ScrollMode.AUTO,
                                            expand=True,
                                        ),
                                        # ── Log tab ───────────────────────────
                                        ft.Container(
                                            content=ft.Column(
                                                [
                                                    ft.Row(
                                                        [
                                                            ft.Text(
                                                                "Log Output",
                                                                size=12,
                                                                color=_LABEL,
                                                                weight=ft.FontWeight.W_600,
                                                                expand=True,
                                                            ),
                                                            ft.IconButton(
                                                                icon=ft.Icons.DELETE_SWEEP,
                                                                icon_size=16,
                                                                icon_color=_TEXT,
                                                                tooltip="Clear log",
                                                                on_click=lambda _e: _clear_log(),
                                                            ),
                                                        ],
                                                        vertical_alignment=ft.CrossAxisAlignment.CENTER,
                                                    ),
                                                    ft.Divider(height=1, color=_BORDER),
                                                    ft.ListView(
                                                        ref=_log_ref,
                                                        expand=True,
                                                        auto_scroll=True,
                                                        spacing=0,
                                                    ),
                                                ],
                                                spacing=4,
                                                expand=True,
                                            ),
                                            bgcolor=_TERMINAL_BG,
                                            border=ft.Border(
                                                left=ft.BorderSide(3, _LOG_ACCENT),
                                                top=ft.BorderSide(1, _BORDER),
                                                right=ft.BorderSide(1, _BORDER),
                                                bottom=ft.BorderSide(1, _BORDER),
                                            ),
                                            border_radius=8,
                                            padding=ft.Padding(left=12, top=10, right=12, bottom=10),
                                            expand=True,
                                        ),
                                    ],
                                ),
                            ],
                            spacing=8,
                            expand=True,
                        ),
                    ),
                ),
                # ── Dry Run pill ───────────────────────────────────────────
                ft.Container(
                    content=ft.Row(
                        [
                            ft.Text("⚠️", size=14),
                            cb_dry_run,
                            ft.Text(
                                "— no files will be written",
                                size=11,
                                color=_ACCENT_AMBER,
                                italic=True,
                            ),
                        ],
                        vertical_alignment=ft.CrossAxisAlignment.CENTER,
                        spacing=4,
                    ),
                    bgcolor="#1a1400",
                    border=ft.Border.all(1, "#4a3800"),
                    border_radius=8,
                    padding=ft.Padding(left=12, top=6, right=12, bottom=6),
                ),
                # ── Progress bar ───────────────────────────────────────────
                ft.ProgressBar(
                    ref=_progress_ref,
                    value=0,
                    visible=True,
                    height=4,
                    color=_ACCENT_BLUE,
                    bgcolor="#1a1f2e",
                    border_radius=2,
                ),
                # ── Bottom action row ─────────────────────────────────────
                ft.Row(
                    [
                        ft.Text(
                            "Ready",
                            ref=_status_ref,
                            size=11,
                            color=_TEXT,
                            expand=True,
                        ),
                        ft.FilledButton(
                            "💾  Save",
                            ref=_save_ref,
                            height=36,
                            style=ft.ButtonStyle(
                                bgcolor={"": "#0d1f3c"},
                                color={"": _ACCENT_BLUE},
                                shape={"": ft.RoundedRectangleBorder(radius=8)},
                                side={"": ft.BorderSide(1, "#1c3a6e")},
                            ),
                            on_click=lambda e: _on_save(e),
                        ),
                        ft.FilledButton(
                            "▶  Start",
                            ref=_start_ref,
                            height=36,
                            style=ft.ButtonStyle(
                                bgcolor={"": _ACCENT_GREEN},
                                color={"": "#ffffff"},
                                shape={"": ft.RoundedRectangleBorder(radius=8)},
                            ),
                            on_click=lambda e: _on_start(e),
                        ),
                        ft.FilledButton(
                            "✖  Cancel",
                            ref=_cancel_ref,
                            height=36,
                            disabled=True,
                            style=ft.ButtonStyle(
                                bgcolor={"disabled": "#2a2a2a", "": _ACCENT_RED},
                                color={"disabled": "#555555", "": "#ffffff"},
                                shape={"": ft.RoundedRectangleBorder(radius=8)},
                            ),
                            on_click=lambda e: _on_cancel(e),
                        ),
                    ],
                    spacing=8,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
            ],
            spacing=16,
            expand=True,
        )
    )

    # ── Log queue + flusher ───────────────────────────────────────────────────
    # Worker threads enqueue (msg, color) tuples; a single daemon drains them
    # every 100 ms in one page.update() — prevents Flet's diff from crashing
    # when dozens of callbacks mutate the ListView simultaneously.

    _log_queue: queue.Queue[tuple[str, str]] = queue.Queue()

    def _append_log(msg: str) -> None:
        """Thread-safe: enqueue a log message. Never calls page.update() directly."""
        if msg.startswith(("✅", "[OK]")):
            color = "#3fb950"
        elif msg.startswith(("❌", "[ERROR]")):
            color = "#f85149"
        elif msg.startswith(("⚠️", "[WARN]", "[CANCELLED]")):
            color = "#d29922"
        elif msg.startswith(("🔍", "[SCAN]")):
            color = "#58a6ff"
        else:
            color = _TEXT
        _log_queue.put((msg, color))

    def _start_log_flusher() -> None:
        """Drain _log_queue every 100 ms in a single page.update() batch."""
        def _flush_loop():
            while True:
                time.sleep(0.1)
                pending: list[tuple[str, str]] = []
                try:
                    while True:
                        pending.append(_log_queue.get_nowait())
                except queue.Empty:
                    pass
                if not pending:
                    continue
                def _do(items: list[tuple[str, str]] = pending) -> None:
                    with _update_lock:
                        for _msg, _color in items:
                            _log_ref.current.controls.append(
                                ft.Text(
                                    _msg,
                                    size=12,
                                    color=_color,
                                    font_family="Courier New",
                                    selectable=True,
                                )
                            )
                        page.update()
                page.run_thread(_do)
        threading.Thread(target=_flush_loop, daemon=True).start()

    _start_log_flusher()

    # ── Handlers ─────────────────────────────────────────────────────────────

    def _set_controls_enabled(enabled: bool) -> None:
        """Enable/disable all config controls atomically."""
        with _update_lock:
            for ctrl in [
                music_field, serato_field, parent_field,
                cb_backup, cb_step1, cb_step2, cb_step3, cb_step4, cb_sort,
                cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
            ]:
                ctrl.disabled = not enabled
            _start_ref.current.disabled = not enabled
            _save_ref.current.disabled = not enabled
            _cancel_ref.current.disabled = enabled
            if _progress_ref.current:
                # Always visible: indeterminate while running, empty bar when idle
                _progress_ref.current.value = None if not enabled else 0
            for run_ref in (
                _run_backup_ref, _run_sort_ref,
                _run0_ref, _run1_ref, _run2_ref, _run3_ref, _run4_ref,
                _scan0_ref, _scan1_ref, _scan2_ref, _scan3_ref, _scan4_ref,
                _sf_scan_ref, _sf_run_ref,
            ):
                if run_ref.current:
                    run_ref.current.disabled = not enabled
            page.update()

    def _guard_required(*checks: tuple[str | None, str]) -> bool:
        """Log a warning and return False if any (value, message) pair is blank."""
        for value, message in checks:
            if not value or not value.strip():
                _append_log(f"⚠️ {message}")
                return False
        return True

    def _run_worker(status_msg: str, work, on_success, error_label: str) -> None:
        """Run `work()` on a daemon thread with the standard clear-log /
        disable-controls / re-enable-controls-on-both-paths lifecycle.

        `work` takes no args and returns a result (or raises).
        `on_success(result)` must return an (log_message, status_message) pair.
        """
        with _update_lock:
            _log_ref.current.controls.clear()
        _status_ref.current.value = status_msg
        _set_controls_enabled(False)

        def _thread():
            try:
                result = work()
                def _done():
                    msg, status = on_success(result)
                    _append_log(msg)
                    _status_ref.current.value = status
                    _set_controls_enabled(True)
                page.run_thread(_done)
            except Exception as exc:
                def _err(_exc=exc):
                    _append_log(f"❌ {error_label} failed: {_exc}")
                    _status_ref.current.value = "Error"
                    _set_controls_enabled(True)
                page.run_thread(_err)

        threading.Thread(target=_thread, daemon=True).start()

    def _run_backup_alone() -> None:
        """Run backup in isolation on a daemon thread."""
        if not _guard_required((serato_field.value, "Serato Path is required.")):
            return
        serato_path = serato_field.value.strip()
        from sync.backup import create_backup
        _run_worker(
            "Running backup…",
            work=lambda: create_backup(serato_path),
            on_success=lambda result: (
                (f"✅ Backup complete: {result}", "Done") if result
                else ("❌ Backup failed — check logs.", "Error")
            ),
            error_label="Backup",
        )

    def _run_sort_alone() -> None:
        """Run sort_crates (Reset A→Z) in isolation on a daemon thread."""
        if not _guard_required((serato_field.value, "Serato Path is required.")):
            return
        serato_path = serato_field.value.strip()
        from sync.pref_sorter import sort_crates
        _run_worker(
            "Sorting crates…",
            work=lambda: sort_crates(serato_path),
            on_success=lambda _result: ("✅ Crates sorted A→Z", "Done"),
            error_label="Sort",
        )

    _STEP_LABELS = {0: "Dupe Manager", 1: "Step 1", 2: "Step 2", 3: "Step 3", 4: "Step 4"}

    def _run_step_alone(step_n: int) -> None:
        """Run a single pipeline step in isolation on a daemon thread."""
        if not _guard_required(
            (music_field.value, "Music Folder is required."),
            (serato_field.value, "Serato Path is required."),
        ):
            return
        try:
            cfg = _build_config(
                music_field, serato_field, parent_field,
                cb_backup, cb_sort,
                cb_step1, cb_step2, cb_step3, cb_step4,
                cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
            )
        except Exception as exc:
            _append_log(f"⚠️ Config error: {exc}")
            return

        step_label = _STEP_LABELS.get(step_n, f"Step {step_n}")
        label = "Dry run" if cfg.dry_run else "Running"
        _cancel_event.clear()

        def _work():
            from sync.pipeline import run_step0, run_step1, run_step2, run_step3, run_step4
            fn_map = {
                0: lambda: run_step0(cfg, log_callback=_append_log),
                1: lambda: run_step1(cfg, log_callback=_append_log, cancel_event=_cancel_event),
                2: lambda: run_step2(cfg, log_callback=_append_log),
                3: lambda: run_step3(cfg, log_callback=_append_log),
                4: lambda: run_step4(cfg, log_callback=_append_log),
            }
            fn_map[step_n]()

        _run_worker(
            f"{label} {step_label}…",
            work=_work,
            on_success=lambda _result: (f"✅ {step_label} complete", "Done"),
            error_label=step_label,
        )

    def _run_scan_alone(step_n: int) -> None:
        """Scan a single pipeline step (forced dry-run) on a daemon thread."""
        if not _guard_required(
            (music_field.value, "Music Folder is required."),
            (serato_field.value, "Serato Path is required."),
        ):
            return
        try:
            cfg = _build_config(
                music_field, serato_field, parent_field,
                cb_backup, cb_sort,
                cb_step1, cb_step2, cb_step3, cb_step4,
                cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
            )
        except Exception as exc:
            _append_log(f"⚠️ Config error: {exc}")
            return

        # Force dry_run regardless of the checkbox state
        scan_cfg = dataclasses.replace(cfg, dry_run=True)
        step_label = _STEP_LABELS.get(step_n, f"Step {step_n}")
        _cancel_event.clear()

        def _work():
            from sync.pipeline import run_step0, run_step1, run_step2, run_step3, run_step4
            fn_map = {
                0: lambda: run_step0(scan_cfg, log_callback=_append_log),
                1: lambda: run_step1(scan_cfg, log_callback=_append_log, cancel_event=_cancel_event),
                2: lambda: run_step2(scan_cfg, log_callback=_append_log),
                3: lambda: run_step3(scan_cfg, log_callback=_append_log),
                4: lambda: run_step4(scan_cfg, log_callback=_append_log),
            }
            fn_map[step_n]()

        _run_worker(
            f"Scanning {step_label}…",
            work=_work,
            on_success=lambda _result: ("🔍 Scan complete — no files written", "Scan complete"),
            error_label=f"Scan {step_label}",
        )

    def _run_session_scan() -> None:
        """Scan for broken session paths (dry_run=True) on a daemon thread.

        Session files always live in ~/Music/_Serato_/History/Sessions/ —
        independent of the external drive Serato path configured above.
        """
        if not _guard_required((music_field.value, "Music Folder is required.")):
            return
        local_serato_path = str(Path.home() / "Music" / "_Serato_")
        music_path = music_field.value.strip()
        _append_log(f"🔍 Session path: {local_serato_path}")

        def _work():
            from sync.session_fixer import scan_broken_paths
            return scan_broken_paths(
                local_serato_path, [music_path], dry_run=True, log_callback=_append_log
            )

        def _on_success(result):
            fixable = len(result["fixable"])
            unfixable = len(result["unfixable"])
            return f"🔍 Scan complete — {fixable} fixable, {unfixable} unfixable", "Scan complete"

        _run_worker(
            "Scanning session paths…",
            work=_work,
            on_success=_on_success,
            error_label="Session scan",
        )

    def _run_session_fix() -> None:
        """Fix broken session paths on a daemon thread.

        Session files always live in ~/Music/_Serato_/History/Sessions/ —
        independent of the external drive Serato path configured above.
        """
        if not _guard_required((music_field.value, "Music Folder is required.")):
            return

        local_serato_path = str(Path.home() / "Music" / "_Serato_")
        music_path = music_field.value.strip()
        _append_log(f"🔍 Session path: {local_serato_path}")

        def _work():
            from sync.session_fixer import fix_broken_paths
            return fix_broken_paths(
                local_serato_path, [music_path], dry_run=False, log_callback=_append_log
            )

        def _on_success(result):
            sessions_fixed, entries_fixed = result
            return (
                f"✅ Session fix complete — {entries_fixed} entries across {sessions_fixed} file(s)",
                "Done",
            )

        _run_worker(
            "Fixing session paths…",
            work=_work,
            on_success=_on_success,
            error_label="Session fix",
        )

    def _on_start(_e) -> None:
        # Validate required fields
        if not _guard_required(
            (music_field.value, "Music Folder is required."),
            (serato_field.value, "Serato Path is required."),
        ):
            return

        # Build config (no save — use 💾 Save for that)
        try:
            cfg = _build_config(
                music_field, serato_field, parent_field,
                cb_backup, cb_sort,
                cb_step1, cb_step2, cb_step3, cb_step4,
                cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
            )
        except Exception as exc:
            _append_log(f"⚠️ Config error: {exc}")
            return

        status_msg = "Dry run running…" if cfg.dry_run else "Running…"
        _cancel_event.clear()

        def _work():
            from sync.pipeline import run_sync
            run_sync(cfg, log_callback=_append_log, cancel_event=_cancel_event)

        _run_worker(
            status_msg,
            work=_work,
            on_success=lambda _result: ("✅ Sync Complete", "Done"),
            error_label="Sync",
        )

    def _on_save(_e) -> None:
        try:
            _build_config(
                music_field, serato_field, parent_field,
                cb_backup, cb_sort,
                cb_step1, cb_step2, cb_step3, cb_step4,
                cb_dupe_scan, dd_detection, dd_move, cb_dry_run,
            ).save(_cfg_path)
            _status_ref.current.value = "Settings saved."
            with _update_lock:
                page.update()
        except Exception as exc:
            _append_log(f"⚠️ Save failed: {exc}")
    def _on_cancel(_e) -> None:
        _cancel_event.set()
        _cancel_ref.current.disabled = True
        _append_log("[CANCELLED] Stop requested — sync will finish current step.")

    def _clear_log() -> None:
        """Clear all log entries and drain any pending queue."""
        # Drain queue first so stale messages don't appear after the clear
        try:
            while True:
                _log_queue.get_nowait()
        except queue.Empty:
            pass
        with _update_lock:
            _log_ref.current.controls.clear()
            page.update()


def _build_config(
    music_field, serato_field, parent_field,
    cb_backup, cb_sort,
    cb_step1, cb_step2, cb_step3, cb_step4,
    cb_dupe_scan, dd_detection, dd_move, cb_dry_run=None,
):
    """Construct a SyncConfig from current GUI control values."""
    from config import SyncConfig
    return SyncConfig(
        music_library_path=(music_field.value or "").strip(),
        serato_library_path=(serato_field.value or "").strip(),
        parent_crate_path=(parent_field.value or "").strip() or None,
        backup_enabled=bool(cb_backup.value),
        clear_library_before_sync=False,
        crate_sorting_enabled=bool(cb_sort.value),
        step1_enabled=bool(cb_step1.value),
        step2_enabled=bool(cb_step2.value),
        step3_enabled=bool(cb_step3.value),
        step4_enabled=bool(cb_step4.value),
        dupe_scan_enabled=bool(cb_dupe_scan.value),
        dupe_detection_mode=dd_detection.value or "off",
        dupe_move_mode=dd_move.value or "false",
        dry_run=bool(cb_dry_run.value) if cb_dry_run is not None else False,
    )


def _load_config(
    music_field, serato_field, parent_field,
    cb_backup, cb_sort,
    cb_step1, cb_step2, cb_step3, cb_step4,
    cb_dupe_scan, dd_detection, dd_move, cb_dry_run=None,
) -> None:
    """Populate controls from config.yaml if it exists. Silent on missing/invalid."""
    try:
        from config import SyncConfig
        cfg_path = Path(__file__).parent / "config.yaml"
        if not cfg_path.exists():
            return
        cfg = SyncConfig.load(cfg_path)
        music_field.value = cfg.music_library_path or ""
        serato_field.value = cfg.serato_library_path or ""
        parent_field.value = cfg.parent_crate_path or ""
        cb_backup.value = cfg.backup_enabled
        cb_sort.value = cfg.crate_sorting_enabled
        cb_step1.value = cfg.step1_enabled
        cb_step2.value = cfg.step2_enabled
        cb_step3.value = cfg.step3_enabled
        cb_step4.value = cfg.step4_enabled
        cb_dupe_scan.value = cfg.dupe_scan_enabled
        dd_detection.value = cfg.dupe_detection_mode
        dd_move.value = cfg.dupe_move_mode
        if cb_dry_run is not None:
            cb_dry_run.value = getattr(cfg, "dry_run", False)
    except Exception:
        pass  # Missing or malformed config — start with defaults


def launch() -> None:
    ft.run(main)


if __name__ == "__main__":
    launch()
