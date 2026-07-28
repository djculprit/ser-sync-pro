"""
cdd-sync-pro Flet GUI — theme tokens and pure widget builders.

Everything here is stateless: no closures over app state, no handler logic.
Widgets that need a click handler take it as an explicit callable parameter.
"""

from __future__ import annotations

import flet as ft

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
_CARD_BG       = "#1c2030"  # elevated pill card bg
_CARD_BORDER   = "#2a3148"  # elevated pill card border


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


def _card_step_row(
    cb: ft.Checkbox,
    run_ref: ft.Ref,
    scan_ref: ft.Ref,
    step_label: str,
    on_scan,
    on_run,
) -> ft.Container:
    """Elevated pill card: [checkbox  label──────────  🔍 Scan  ▶ Run]"""
    scan_btn = _scan_button(scan_ref, on_scan)
    run_btn = _run_button(run_ref, on_run)
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
        border=ft.Border.all(1, _CARD_BORDER),
        border_radius=8,
        padding=ft.Padding(left=8, top=4, right=8, bottom=4),
    )


def _scan_run_buttons(scan_ref: ft.Ref, run_ref: ft.Ref, on_scan, on_run) -> ft.Row:
    """Compact [🔍 Scan  ▶ Run] pair for a section header_action slot."""
    scan_btn = _scan_button(scan_ref, on_scan, height=28)
    run_btn = _run_button(run_ref, on_run, height=28)
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
        border=ft.Border.all(1, _CARD_BORDER),
        border_radius=8,
        padding=ft.Padding(left=8, top=4, right=8, bottom=4),
    )


def _card_action_row(cb: ft.Checkbox, ref: ft.Ref, label: str, on_click) -> ft.Container:
    """Checkbox + label + single ▶ Run button pinned right."""
    run_btn = _run_button(ref, on_click)
    cb.label = ""
    return ft.Container(
        content=ft.Row(
            [
                cb,
                ft.Text(label, size=12, color=_LABEL, expand=True),
                run_btn,
            ],
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
            spacing=6,
        ),
        bgcolor=_CARD_BG,
        border=ft.Border.all(1, _CARD_BORDER),
        border_radius=8,
        padding=ft.Padding(left=8, top=4, right=8, bottom=4),
    )


def _card_session_fixer_row(
    cb: ft.Checkbox,
    scan_ref: ft.Ref,
    run_ref: ft.Ref,
    on_scan,
    on_run,
) -> ft.Container:
    """Session Fixer pill: [checkbox  label──────────  🔍 Scan  ▶ Run]"""
    scan_btn = _scan_button(scan_ref, on_scan)
    run_btn = _run_button(run_ref, on_run)
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
        border=ft.Border.all(1, _CARD_BORDER),
        border_radius=8,
        padding=ft.Padding(left=8, top=4, right=8, bottom=4),
    )
