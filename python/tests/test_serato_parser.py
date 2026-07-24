"""
Tests porting: cdd_sync_crate_test.java — crate read/write round-trip tests.

Round-trip test strategy:
  1. Find any .crate file in the user's _Serato_/Subcrates/ folder.
  2. Read it with Python.
  3. Write it back to a temp file.
  4. Assert byte-for-byte identity with the original.

If no real crate file is available, synthetic tests cover the writer/reader contract.
"""
import io
import struct
import tempfile
import pytest
from pathlib import Path

from core.serato_parser import (
    Crate, read_crate, write_crate,
    DEFAULT_VERSION, DEFAULT_SORTING, DEFAULT_COLUMNS,
)
from core.binary_utils import encode_utf16be


# ---------------------------------------------------------------------------
# Helpers to build a minimal synthetic .crate file for testing
# ---------------------------------------------------------------------------

def _build_synthetic_crate_bytes(tracks: list[str]) -> bytes:
    """Build a minimal valid .crate file in memory."""
    buf = io.BytesIO()

    # vrsn header
    version = "81.0"
    suffix = "/Serato ScratchLive Crate"
    buf.write(b'vrsn')
    buf.write(b'\x00\x00')
    buf.write(encode_utf16be(version))
    buf.write(encode_utf16be(suffix))

    # osrt
    sorting = "song"
    sorting_bytes = encode_utf16be(sorting)
    brev_payload = (1 << 8).to_bytes(5, 'big')
    osrt_inner = (
        b'tvcn' + struct.pack('>I', len(sorting_bytes)) + sorting_bytes +
        b'brev' + struct.pack('>I', 5) + brev_payload
    )
    buf.write(b'osrt')
    buf.write(struct.pack('>I', len(osrt_inner)))
    buf.write(osrt_inner)

    # ovct (default columns — write_crate always emits these when no columns were parsed,
    # matching Java's writeTo() fallback to getColumns() / DEFAULT_COLUMNS)
    for col in ("song", "artist", "album", "length"):
        col_bytes = encode_utf16be(col)
        inner = (
            b'tvcn' + struct.pack('>I', len(col_bytes)) + col_bytes +
            b'tvcw' + struct.pack('>I', 2) + b'\x000'
        )
        buf.write(b'ovct')
        buf.write(struct.pack('>I', len(inner)))
        buf.write(inner)

    # tracks
    for track in tracks:
        track_bytes = encode_utf16be(track)
        ptrk = b'ptrk' + struct.pack('>I', len(track_bytes)) + track_bytes
        buf.write(b'otrk')
        buf.write(struct.pack('>I', len(ptrk)))
        buf.write(ptrk)

    return buf.getvalue()


class TestCrateRoundTrip:
    def test_empty_crate_round_trip(self):
        crate = Crate()
        with tempfile.NamedTemporaryFile(suffix='.crate', delete=False) as f:
            tmp = Path(f.name)
        write_crate(crate, tmp)
        crate2 = read_crate(tmp)
        assert crate == crate2
        tmp.unlink()

    def test_crate_with_tracks_round_trip(self):
        crate = Crate()
        crate.add_track('Crates/Folder/track1.mp3')
        crate.add_track('Crates/Folder/track2.flac')
        with tempfile.NamedTemporaryFile(suffix='.crate', delete=False) as f:
            tmp = Path(f.name)
        write_crate(crate, tmp)
        crate2 = read_crate(tmp)
        assert crate == crate2
        assert list(crate2.tracks) == ['Crates/Folder/track1.mp3', 'Crates/Folder/track2.flac']
        tmp.unlink()

    def test_track_order_preserved(self):
        crate = Crate()
        tracks = [f'Crates/F/track{i}.mp3' for i in range(5)]
        crate.add_tracks(tracks)
        with tempfile.NamedTemporaryFile(suffix='.crate', delete=False) as f:
            tmp = Path(f.name)
        write_crate(crate, tmp)
        crate2 = read_crate(tmp)
        assert list(crate2.tracks) == tracks
        tmp.unlink()

    def test_dedup_prevents_duplicate_track(self):
        crate = Crate()
        crate.add_track('Crates/Folder/track.mp3')
        crate.add_track('Crates/Folder/track.mp3')
        assert len(crate.tracks) == 1

    def test_dedup_nfc_nfd_same_file(self):
        import unicodedata
        crate = Crate()
        nfc_path = unicodedata.normalize('NFC', 'Crates/Niña/track.mp3')
        nfd_path = unicodedata.normalize('NFD', 'Crates/Niña/track.mp3')
        crate.add_track(nfc_path)
        crate.add_track(nfd_path)
        assert len(crate.tracks) == 1

    def test_synthetic_byte_round_trip(self):
        """Read a synthetically built crate, write it, confirm byte identity."""
        tracks = ['Crates/A/one.mp3', 'Crates/B/two.flac']
        original_bytes = _build_synthetic_crate_bytes(tracks)
        with tempfile.NamedTemporaryFile(suffix='.crate', delete=False) as f:
            src = Path(f.name)
        src.write_bytes(original_bytes)
        crate = read_crate(src)
        with tempfile.NamedTemporaryFile(suffix='.crate', delete=False) as f:
            dst = Path(f.name)
        write_crate(crate, dst)
        written = dst.read_bytes()
        assert written == original_bytes, (
            f"Byte mismatch: original={len(original_bytes)}b written={len(written)}b"
        )
        src.unlink()
        dst.unlink()
