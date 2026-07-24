"""
Serato binary file parser and writer.
Handles .crate files, database V2, and neworder.pref.

Binary format (all Serato files):
  - Header: 4-byte ASCII tag + variable content
  - TLV blocks: 4-byte tag | 4-byte BE length | payload bytes
  - Strings: UTF-16BE encoded
  - Integers: big-endian

Ports: cdd_sync_crate.java (readFrom, writeTo, addTrack, setTracksRaw, equals)
       cdd_sync_database.java (readFrom, containsTrack*, getOriginalPathByFilename)
"""

import io
import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .binary_utils import decode_utf16be, encode_utf16be, read_big_endian_int, read_big_endian_long
from .path_utils import normalize_for_dedup, normalize_path_for_database, resolve_serato_path


# ---------------------------------------------------------------------------
# Crate model
# ---------------------------------------------------------------------------

DEFAULT_VERSION = "81.0"
DEFAULT_SORTING = "song"
DEFAULT_SORTING_REV = 1
DEFAULT_COLUMNS = ["song", "artist", "album", "length"]

# Crate vrsn header suffix (UTF-16BE after the 4-char version string)
_CRATE_HEADER_SUFFIX = "/Serato ScratchLive Crate"


@dataclass
class Crate:
    version: Optional[str] = None
    sorting: Optional[str] = None
    sorting_rev: Optional[int] = None
    columns: list = field(default_factory=list)
    tracks: list = field(default_factory=list)
    # Raw payloads preserved verbatim from an existing crate (column widths etc.)
    raw_osrt_payload: Optional[bytes] = None
    raw_ovct_payloads: list = field(default_factory=list)
    # Reference to database for resolveSeratoPath()
    database: object = None
    # NFC-normalised filename set for O(1) dedup
    _dedup_set: set = field(default_factory=set, repr=False)

    def get_version(self) -> str:
        return self.version if self.version else DEFAULT_VERSION

    def get_sorting(self) -> str:
        return self.sorting if self.sorting else DEFAULT_SORTING

    def get_sorting_rev(self) -> int:
        return self.sorting_rev if self.sorting_rev is not None else DEFAULT_SORTING_REV

    def get_columns(self) -> list:
        return self.columns if self.columns else DEFAULT_COLUMNS

    def set_database(self, db) -> None:
        self.database = db

    def add_track(self, track_path: str) -> None:
        key = normalize_for_dedup(track_path)
        if key not in self._dedup_set:
            self._dedup_set.add(key)
            self.tracks.append(resolve_serato_path(track_path, self.database))

    def add_tracks(self, track_paths) -> None:
        for p in track_paths:
            self.add_track(p)

    def set_tracks_raw(self, raw_tracks: list) -> None:
        """Bypass dedup — use only when rewriting existing crates."""
        self.tracks = list(raw_tracks)
        self._dedup_set = {normalize_for_dedup(t) for t in raw_tracks}

    def get_uniform_track_name(self, name: str) -> str:
        return normalize_path_for_database(name)

    def __eq__(self, other) -> bool:
        if not isinstance(other, Crate):
            return False
        if self.get_version() != other.get_version():
            return False
        if self.get_sorting() != other.get_sorting():
            return False
        if self.get_sorting_rev() != other.get_sorting_rev():
            return False
        if self.get_columns() != other.get_columns():
            return False
        if len(self.tracks) != len(other.tracks):
            return False
        for t1, t2 in zip(self.tracks, other.tracks):
            if normalize_path_for_database(t1) != normalize_path_for_database(t2):
                return False
        return True

    def __hash__(self):
        return hash((
            self.get_version(),
            self.get_sorting(),
            self.get_sorting_rev(),
            tuple(self.get_columns()),
            tuple(normalize_path_for_database(t) for t in self.tracks),
        ))


# ---------------------------------------------------------------------------
# Crate reader
# ---------------------------------------------------------------------------

def _walk_payload_for_tag(payload: bytes, target_tag: str) -> Optional[str]:
    """Return first UTF-16BE value for target_tag in TLV payload, or None."""
    pos = 0
    tag_bytes = target_tag.encode('ascii')
    while pos + 8 <= len(payload):
        inner_tag = payload[pos:pos + 4]
        inner_len = read_big_endian_int(payload, pos + 4)
        pos += 8
        if pos + inner_len > len(payload):
            break
        if inner_tag == tag_bytes:
            return decode_utf16be(payload[pos:pos + inner_len])
        pos += inner_len
    return None


def _extract_osrt(payload: bytes, crate: Crate) -> None:
    """Parse osrt block: tvcn (sorting name) + brev (sorting rev)."""
    pos = 0
    while pos + 8 <= len(payload):
        inner_tag = payload[pos:pos + 4].decode('ascii', errors='replace')
        inner_len = read_big_endian_int(payload, pos + 4)
        pos += 8
        if pos + inner_len > len(payload):
            break
        if inner_tag == 'tvcn':
            crate.sorting = decode_utf16be(payload[pos:pos + inner_len])
        elif inner_tag == 'brev':
            crate.sorting_rev = read_big_endian_long(payload, pos, inner_len)
        pos += inner_len


def read_crate(path: Path) -> Crate:
    """
    Parse a Serato .crate binary file into a Crate model.
    Single unified TLV pass — no stream slippage possible.
    Ports: cdd_sync_crate.readFrom()
    """
    crate = Crate()
    data = path.read_bytes()
    buf = io.BytesIO(data)

    # --- vrsn header ---
    tag = buf.read(4)
    if tag != b'vrsn':
        raise ValueError(f"Not a Serato crate file (expected 'vrsn', got {tag!r}): {path}")

    # 2-byte literal zero
    buf.read(2)

    # 4-char version string as UTF-16BE (8 bytes)
    version_bytes = buf.read(8)
    crate.version = decode_utf16be(version_bytes)

    # Remainder of vrsn block: "/Serato ScratchLive Crate" in UTF-16BE
    suffix_len = len(_CRATE_HEADER_SUFFIX) * 2
    buf.read(suffix_len)

    # --- TLV pass ---
    while True:
        tag_bytes = buf.read(4)
        if len(tag_bytes) < 4:
            break
        tag = tag_bytes.decode('ascii', errors='replace')
        length_bytes = buf.read(4)
        if len(length_bytes) < 4:
            break
        length = struct.unpack('>I', length_bytes)[0]
        payload = buf.read(length)

        if tag == 'otrk':
            path_str = _walk_payload_for_tag(payload, 'ptrk')
            if path_str:
                crate.add_track(path_str)
        elif tag == 'ovct':
            col = _walk_payload_for_tag(payload, 'tvcn')
            if col:
                crate.columns.append(col)
                crate.raw_ovct_payloads.append(payload)
        elif tag == 'osrt':
            _extract_osrt(payload, crate)
            crate.raw_osrt_payload = payload
        # Unknown tags: payload consumed, move on

    return crate


# ---------------------------------------------------------------------------
# Crate writer
# ---------------------------------------------------------------------------

def _write_tag_block(buf: io.BytesIO, tag: str, payload: bytes) -> None:
    buf.write(tag.encode('ascii'))
    buf.write(struct.pack('>I', len(payload)))
    buf.write(payload)


def _build_osrt_payload(crate: Crate) -> bytes:
    inner = io.BytesIO()
    sorting_bytes = encode_utf16be(crate.get_sorting())
    inner.write(b'tvcn')
    inner.write(struct.pack('>I', len(sorting_bytes)))
    inner.write(sorting_bytes)
    inner.write(b'brev')
    rev = crate.get_sorting_rev()
    rev_bytes = rev.to_bytes(1, 'big')
    inner.write(struct.pack('>I', 1))
    inner.write(rev_bytes)
    return inner.getvalue()


def _build_ovct_payload(column: str) -> bytes:
    inner = io.BytesIO()
    col_bytes = encode_utf16be(column)
    inner.write(b'tvcn')
    inner.write(struct.pack('>I', len(col_bytes)))
    inner.write(col_bytes)
    inner.write(b'tvcw')
    inner.write(struct.pack('>I', 2))
    inner.write(b'\x00' + b'0')
    return inner.getvalue()


def write_crate(crate: Crate, path: Path) -> None:
    """
    Write a Crate model to a .crate binary file.
    Ports: cdd_sync_crate.writeTo()
    """
    buf = io.BytesIO()

    # vrsn header
    buf.write(b'vrsn')
    buf.write(b'\x00\x00')
    buf.write(encode_utf16be(crate.get_version()))
    buf.write(encode_utf16be(_CRATE_HEADER_SUFFIX))

    # osrt — verbatim if captured, else reconstruct
    if crate.raw_osrt_payload is not None:
        _write_tag_block(buf, 'osrt', crate.raw_osrt_payload)
    else:
        _write_tag_block(buf, 'osrt', _build_osrt_payload(crate))

    # ovct (columns) — verbatim if captured, else reconstruct
    if crate.raw_ovct_payloads:
        for ovct_payload in crate.raw_ovct_payloads:
            _write_tag_block(buf, 'ovct', ovct_payload)
    else:
        for col in crate.get_columns():
            _write_tag_block(buf, 'ovct', _build_ovct_payload(col))

    # otrk (tracks)
    for track_raw in crate.tracks:
        track = normalize_path_for_database(track_raw)
        track_bytes = encode_utf16be(track)
        ptrk_payload = b'ptrk' + struct.pack('>I', len(track_bytes)) + track_bytes
        _write_tag_block(buf, 'otrk', ptrk_payload)

    path.write_bytes(buf.getvalue())


# ---------------------------------------------------------------------------
# Database V2 parser
# ---------------------------------------------------------------------------

@dataclass
class DatabaseEntry:
    path: str          # original encoding from file (NFD)
    file_size: int = 0


class SeratoDatabase:
    """
    Parses Serato 'database V2' binary file.
    Ports: cdd_sync_database.java
    """

    def __init__(self):
        # keyed by normalize_path(path)|size → DatabaseEntry
        self._by_path: dict = {}
        # keyed by normalize_for_dedup(path) → first DatabaseEntry seen
        self._by_filename: dict = {}

    @classmethod
    def read_from(cls, db_path: Path) -> 'SeratoDatabase':
        from .path_utils import normalize_path
        db = cls()
        data = db_path.read_bytes()
        buf = io.BytesIO(data)

        # Skip vrsn header
        tag = buf.read(4)
        if tag != b'vrsn':
            raise ValueError(f"Not a Serato database file: {db_path}")
        # vrsn block: 2 zero bytes + UTF-16BE version string + UTF-16BE suffix
        # The version length varies (e.g. "81.0" = 8 bytes, "2.0" = 6 bytes).
        # Skip the entire vrsn blob by scanning forward for the next 4-byte ASCII tag.
        buf.read(2)  # two literal zero bytes
        remaining = data[buf.tell():]
        skip = 0
        # Find the first byte position where a 4-byte printable ASCII sequence starts
        # followed by a valid big-endian length that fits in remaining data.
        for i in range(len(remaining) - 8):
            candidate = remaining[i:i + 4]
            if all(0x20 <= b < 0x7F for b in candidate):
                cand_len = struct.unpack('>I', remaining[i + 4:i + 8])[0]
                if i + 8 + cand_len <= len(remaining):
                    skip = i
                    break
        buf.read(skip)  # skip past vrsn payload to first TLV tag

        while True:
            tag_bytes = buf.read(4)
            if len(tag_bytes) < 4:
                break
            tag = tag_bytes.decode('ascii', errors='replace')
            length_bytes = buf.read(4)
            if len(length_bytes) < 4:
                break
            length = struct.unpack('>I', length_bytes)[0]
            payload = buf.read(length)

            if tag == 'otrk':
                entry = cls._parse_otrk(payload)
                if entry:
                    norm_key = normalize_path(entry.path) + '|' + str(entry.file_size)
                    db._by_path[norm_key] = entry
                    fn_key = normalize_for_dedup(entry.path)
                    if fn_key not in db._by_filename:
                        db._by_filename[fn_key] = entry

        return db

    @staticmethod
    def _parse_otrk(payload: bytes) -> Optional[DatabaseEntry]:
        entry = DatabaseEntry(path='')
        pos = 0
        while pos + 8 <= len(payload):
            inner_tag = payload[pos:pos + 4].decode('ascii', errors='replace')
            inner_len = read_big_endian_int(payload, pos + 4)
            pos += 8
            if pos + inner_len > len(payload):
                break
            if inner_tag == 'pfil':
                entry.path = decode_utf16be(payload[pos:pos + inner_len])
            elif inner_tag == 'tsiz':
                entry.file_size = read_big_endian_long(payload, pos, inner_len)
            pos += inner_len
        return entry if entry.path else None

    def contains_track_by_path(self, track_path: str, file_size: int) -> bool:
        from .path_utils import normalize_path
        key = normalize_path(track_path) + '|' + str(file_size)
        return key in self._by_path

    def contains_track_by_filename(self, track_path: str, file_size: int) -> bool:
        fn_key = normalize_for_dedup(track_path)
        entry = self._by_filename.get(fn_key)
        return entry is not None and entry.file_size == file_size

    def get_original_path_by_filename(self, track_path: str) -> Optional[str]:
        """Return the exact DB encoding (NFD-preserved) for a filename, or None."""
        fn_key = normalize_for_dedup(track_path)
        entry = self._by_filename.get(fn_key)
        return entry.path if entry else None

    def get_all_track_paths(self) -> list:
        return [e.path for e in self._by_path.values()]
