"""
Integration tests for the sync pipeline — 4 tests covering:
  1. dry-run produces no writes
  2. step4 creates new .crate files
  3. step2 fixes a broken path in an existing crate
  4. crate sorting creates a sorted neworder.pref
"""

from __future__ import annotations

import os
import struct
import sys
import tempfile
from pathlib import Path

import pytest

# Ensure project root is on path
sys.path.insert(0, str(Path(__file__).parent.parent))

from config import SyncConfig
from core.binary_utils import encode_utf16be
from core.serato_parser import Crate, read_crate, write_crate
from sync.pipeline import run_sync


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _make_serato(vol: Path) -> Path:
    """Create a minimal _Serato_ directory with empty database V2."""
    serato = vol / "_Serato_"
    serato.mkdir(parents=True, exist_ok=True)
    (serato / "Subcrates").mkdir(exist_ok=True)
    _write_database(serato / "database V2", [])
    return serato


def _make_music(vol: Path, structure: dict) -> Path:
    """
    Create music directory from a dict of {relative_path: content_bytes}.
    e.g. {"Jazz/track.mp3": b"x"}
    """
    music = vol / "music"
    for rel, data in structure.items():
        p = music / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
    return music


def _write_database(db_path: Path, tracks: list[str]) -> None:
    """Write a minimal Serato database V2 binary with given track paths."""
    import io
    buf = io.BytesIO()
    buf.write(b"vrsn\x00\x00")
    buf.write(encode_utf16be("81.0"))
    buf.write(encode_utf16be("/Serato ScratchLive Database"))
    for track in tracks:
        path_bytes = encode_utf16be(track)
        pfil = b"pfil" + struct.pack(">I", len(path_bytes)) + path_bytes
        buf.write(b"otrk" + struct.pack(">I", len(pfil)) + pfil)
    db_path.write_bytes(buf.getvalue())


def _make_config(music: Path, serato: Path, **kwargs) -> SyncConfig:
    return SyncConfig(
        music_library_path=str(music),
        serato_library_path=str(serato),
        backup_enabled=False,
        dry_run=kwargs.get("dry_run", False),
        step0_enabled=kwargs.get("step0_enabled", False),
        step1_enabled=kwargs.get("step1_enabled", False),
        step2_enabled=kwargs.get("step2_enabled", False),
        step3_enabled=kwargs.get("step3_enabled", False),
        step4_enabled=kwargs.get("step4_enabled", False),
        crate_sorting_enabled=kwargs.get("crate_sorting_enabled", False),
    )


# ---------------------------------------------------------------------------
# Test 1: dry-run produces no writes
# ---------------------------------------------------------------------------

def test_dry_run_no_writes():
    """A dry-run should not create any crate files or modify _Serato_."""
    with tempfile.TemporaryDirectory() as vol_str:
        vol = Path(vol_str)
        music = _make_music(vol, {"Rock/song.mp3": b"x" * 100})
        serato = _make_serato(vol)
        db_mtime_before = (serato / "database V2").stat().st_mtime

        cfg = _make_config(
            music, serato,
            dry_run=True,
            step4_enabled=True,
        )
        run_sync(cfg)

        subcrates = serato / "Subcrates"
        crate_files = list(subcrates.glob("*.crate"))
        assert crate_files == [], f"dry-run created crates: {crate_files}"

        db_mtime_after = (serato / "database V2").stat().st_mtime
        assert db_mtime_before == db_mtime_after, "dry-run modified database V2"


# ---------------------------------------------------------------------------
# Test 2: step4 creates .crate files for each library folder
# ---------------------------------------------------------------------------

def test_step4_creates_crates():
    """Step 4 should write one .crate per library subdirectory."""
    with tempfile.TemporaryDirectory() as vol_str:
        vol = Path(vol_str)
        music = _make_music(vol, {
            "Jazz/track1.mp3": b"x" * 100,
            "Rock/track2.mp3": b"x" * 100,
        })
        serato = _make_serato(vol)

        cfg = _make_config(music, serato, step4_enabled=True)
        run_sync(cfg)

        subcrates = serato / "Subcrates"
        crate_names = {f.stem for f in subcrates.glob("*.crate")}
        assert "Jazz" in crate_names, f"Jazz.crate missing. Found: {crate_names}"
        assert "Rock" in crate_names, f"Rock.crate missing. Found: {crate_names}"

        jazz_crate = read_crate(subcrates / "Jazz.crate")
        assert len(jazz_crate.tracks) == 1
        rock_crate = read_crate(subcrates / "Rock.crate")
        assert len(rock_crate.tracks) == 1


# ---------------------------------------------------------------------------
# Test 3: step2 fixes a broken path in an existing crate
# ---------------------------------------------------------------------------

def test_step2_fixes_broken_path():
    """Step 2 should replace a stale track path in a crate with the correct library path."""
    with tempfile.TemporaryDirectory() as vol_str:
        vol = Path(vol_str)
        real_path = vol / "music" / "Jazz" / "track.mp3"
        real_path.parent.mkdir(parents=True)
        real_path.write_bytes(b"x" * 100)

        serato = _make_serato(vol)
        subcrates = serato / "Subcrates"

        # Write a crate with a broken (stale) path
        broken_path = "OldVolume/Jazz/track.mp3"
        stale_crate = Crate()
        stale_crate.set_tracks_raw([broken_path])
        write_crate(stale_crate, subcrates / "Jazz.crate")

        cfg = _make_config(
            vol / "music", serato,
            step2_enabled=True,
        )
        run_sync(cfg)

        fixed_crate = read_crate(subcrates / "Jazz.crate")
        assert len(fixed_crate.tracks) == 1
        fixed = fixed_crate.tracks[0]
        assert "OldVolume" not in fixed, f"Path not fixed: {fixed}"
        assert "track.mp3" in fixed, f"Filename missing: {fixed}"


# ---------------------------------------------------------------------------
# Test 4: crate sorting creates sorted neworder.pref
# ---------------------------------------------------------------------------

def test_crate_sorting_creates_sorted_neworder_pref():
    """Enabling crate_sorting should produce a neworder.pref with crates in alpha order."""
    with tempfile.TemporaryDirectory() as vol_str:
        vol = Path(vol_str)
        music = _make_music(vol, {
            "Zoo/track.mp3": b"x" * 100,
            "Alpha/track.mp3": b"x" * 100,
            "Metal/track.mp3": b"x" * 100,
        })
        serato = _make_serato(vol)

        cfg = _make_config(
            music, serato,
            step4_enabled=True,
            crate_sorting_enabled=True,
        )
        run_sync(cfg)

        pref = serato / "neworder.pref"
        assert pref.exists(), "neworder.pref was not created"

        content = pref.read_bytes().decode("utf-16-be")
        names = [
            line[len("[crate]"):]
            for line in content.strip().splitlines()
            if line.startswith("[crate]")
        ]
        assert names == sorted(names), f"Crates not sorted: {names}"
        assert set(names) == {"Alpha", "Metal", "Zoo"}, f"Unexpected crates: {names}"
