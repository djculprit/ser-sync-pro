"""
Low-level binary I/O for Serato file formats.
All Serato files use UTF-16BE strings and big-endian integers.
Ports: cdd_sync_input_stream.java, cdd_sync_output_stream.java
"""

import struct
from pathlib import Path


def read_big_endian_int(data: bytes, offset: int) -> int:
    """Read 4-byte big-endian unsigned int from byte array at offset."""
    return struct.unpack_from('>I', data, offset)[0]


def read_big_endian_long(data: bytes, offset: int, size: int) -> int:
    """Read variable-length big-endian value (up to 8 bytes)."""
    value = 0
    for i in range(size):
        value = (value << 8) | data[offset + i]
    return value


def decode_utf16be(data: bytes) -> str:
    """Decode bytes as UTF-16BE string (Serato's string format)."""
    return data.decode('utf-16-be')


def encode_utf16be(s: str) -> bytes:
    """Encode string as UTF-16BE bytes."""
    return s.encode('utf-16-be')


def read_file(path: Path) -> bytes:
    """Read entire file as bytes."""
    return path.read_bytes()


def write_file(path: Path, data: bytes) -> None:
    """Write bytes to file (atomic via temp + rename not needed at this scale)."""
    path.write_bytes(data)


def format_size(size_bytes: int) -> str:
    """Human-readable file size string."""
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if size_bytes < 1024:
            return f"{size_bytes:.1f} {unit}"
        size_bytes //= 1024
    return f"{size_bytes:.1f} PB"
