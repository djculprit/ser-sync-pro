# GUI Cleanup Plan — `python/gui.py`

## Context

`python/gui.py` is 1284 lines and works correctly, but has grown three
duplication problems. This plan fixes them without changing any behavior —
every button should do exactly what it does today, just via less repeated
code. No `sync/*` or `core/*` logic should be touched; this is GUI-file-only.

**Before starting:** run `git status` / `git diff python/gui.py`. There is
already an uncommitted in-progress change in the working tree that wraps
several `page.update()` calls in `_update_lock` (a thread-safety fix, not
yet committed). Do not discard it. Either ask the user whether to commit it
first as its own commit, or carry it forward faithfully inside the new
helpers introduced below — every place that currently does
`with _update_lock: ...; page.update()` must keep doing so after refactor.

## Problem summary (see full analysis in prior conversation if available)

1. Six handler functions (`_run_backup_alone`, `_run_sort_alone`,
   `_run_step_alone`, `_run_scan_alone`, `_run_session_scan`,
   `_run_session_fix`, `_on_start`) repeat the same ~40-line skeleton:
   validate → clear log → set status → disable controls → spawn thread →
   try/except → re-enable controls on both paths. ~250 duplicated lines,
   and a real bug risk (forgetting to re-enable controls on a new error
   path locks the UI).
2. Five widget-factory functions (`_card_step_row`, `_scan_run_buttons`,
   `_card_backup_row`, `_card_sort_row`, `_card_session_fixer_row`) each
   rebuild an identical scan-button and run-button `ButtonStyle`, just
   swapping the click handler.
3. (Lower priority, do only if explicitly asked) Everything lives as nested
   closures inside one ~1100-line `async def main(page)`.

Fix #1 and #2 in this plan. Leave #3 alone unless the user asks for it —
it's a bigger, riskier restructuring with less payoff.

## Phase 1 — Extract the duplicated async-handler skeleton

Add two small helpers near the top of `async def main`, after
`_update_lock` is defined and before the first handler that needs them
(around where `_set_controls_enabled` is currently defined, ~gui.py:873):

```python
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
```

These signatures are a strong starting point, not gospel — if a handler
doesn't fit cleanly (e.g. needs to log an extra line before the thread
starts, like the session-fixer's "🔍 Session path: …" line), call
`_append_log(...)` yourself right before calling `_run_worker`, don't bend
the helper's shape to accommodate one caller.

Then rewrite each handler to call these helpers. Example (`_run_backup_alone`,
currently gui.py:898-928):

```python
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
```

Apply the same transformation to:

- `_run_sort_alone` (gui.py:930-957)
- `_run_step_alone` (gui.py:961-1011) — keep the existing synchronous
  `_build_config(...)` call (and its own try/except → `_append_log` +
  `return` on failure) *before* calling `_run_worker`; only the threaded
  part moves into `work`. Also keep the `_cancel_event.clear()` call before
  `_run_worker`.
- `_run_scan_alone` (gui.py:1013-1065) — same pattern, forces
  `dataclasses.replace(cfg, dry_run=True)` before `_run_worker`.
- `_run_session_scan` (gui.py:1067-1104) — emit the
  `f"🔍 Session path: {local_serato_path}"` log line right before calling
  `_run_worker` (do this with a plain `_append_log` call, not inside
  `work`, so it shows up immediately rather than after the thread starts).
- `_run_session_fix` (gui.py:1106-1142) — same as above.
- `_on_start` (gui.py:1144-1189) — keep `_cancel_event.clear()` and the
  dry-run-aware status string (`"Dry run running…" if cfg.dry_run else
  "Running…"`) computed before calling `_run_worker`.

`_STEP_LABELS` (gui.py:959) and the `fn_map` dispatch dicts inside
`_run_step_alone`/`_run_scan_alone` stay as-is — they're not part of the
duplicated skeleton, just per-step dispatch tables.

### Verify Phase 1

- `cd python && pytest` — must still be all-green (these are GUI handlers,
  not covered by pytest, but this confirms nothing in `sync/`/`core/` broke
  from an accidental import edit).
- Launch the GUI (`python main.py`) and manually exercise, at minimum:
  Backup Run, one pipeline step's Scan and Run, Session Fixer Scan, and
  Cancel mid-run. Confirm the log panel populates, the status line updates,
  and controls re-enable after both success and a forced failure (e.g. run
  Start with an invalid Music Folder path to hit the error branch).

## Phase 2 — Deduplicate the scan/run button builders

The scan-button and run-button `ft.FilledButton` blocks inside
`_card_step_row` (gui.py:238-265), `_scan_run_buttons` (gui.py:296-323),
`_card_backup_row`'s run button (gui.py:349-362), `_card_sort_row`'s run
button (gui.py:387-400), and `_card_session_fixer_row` (gui.py:429-456)
are byte-for-byte identical `ButtonStyle` dicts differing only in `ref` and
`on_click`. Extract two factories near the other widget helpers
(alongside `_checkbox`/`_dropdown`/`_field`, ~gui.py:112-143):

```python
def _scan_button(ref: ft.Ref, on_click) -> ft.FilledButton:
    return ft.FilledButton(
        "🔍  Scan",
        ref=ref,
        height=30,
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


def _run_button(ref: ft.Ref, on_click) -> ft.FilledButton:
    return ft.FilledButton(
        "▶  Run",
        ref=ref,
        height=30,
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
```

Note `_scan_run_buttons` (gui.py:294-324) uses `height=28` for both
buttons instead of `30` — that's an existing inconsistency, not a typo to
"fix" silently. Add a `height: int = 30` parameter to both factories so
that call site can pass `height=28` and every other call site keeps `30`,
preserving current visual behavior exactly.

Replace each of the five call sites' inline button construction with a call
to `_scan_button(...)` / `_run_button(...)`, keeping each site's own
`on_click` lambda (e.g. `lambda _e, n=step_n: _run_scan_alone(n)`) unchanged.

### Verify Phase 2

- Launch the GUI and visually compare every card row (Backup, Steps 1-4,
  Reset A→Z, Dupe Manager header, Session Fixer) against a screenshot taken
  before this phase — button colors, sizes, and spacing must be pixel-identical.
- Click every Scan and Run button once each to confirm `on_click` wiring
  survived the extraction.

## Out of scope for this plan

- Splitting `gui.py` into multiple modules (widgets vs. layout vs.
  handlers). Worth doing eventually but is a bigger, judgment-call-heavy
  restructuring — only take this on if the user asks for it after seeing
  Phases 1-2 land.
- Any change to `sync/*`, `core/*`, or `archive/java/`.
- Adding new features, tests for GUI code, or type hints beyond what's
  already there.

## Completion

When both phases are done and verified, add an entry to `md/AGENT_LOG.md`
(newest entry at the top, below the existing comment) following the
existing format: Task / Files Changed / What Was Done / Docs to Update.
Then move this directory to `md/actions/archive/gui-cleanup/` per the
repo's existing archival convention (see prior entries in AGENT_LOG.md,
e.g. `py-bugfix`, `session-fixer`).
