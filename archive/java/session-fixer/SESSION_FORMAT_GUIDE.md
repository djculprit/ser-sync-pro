# Serato .session File Format Guide

This document details the binary format of Serato's `.session` history files, located in the `History/Sessions` directory. This guide is intended for developers building applications that need to read or modify these files.

## Overview

* **File Extension**: `.session`
* **Encoding**: Big-Endian (Network Byte Order)
* **String Encoding**: UTF-16BE
* **Structure**: Tag-Length-Value (TLV) based, hierarchical.

## File Structure

The file consists of a header followed by a sequence of track entries.

### 1. Header

The file starts with a version header.

| Offset | Type | Value (String) | Description |
| :--- | :--- | :--- | :--- |
| 0 | `char[4]` | `vrsn` | Magic marker indicating the start of the version block. |
| 4 | `int32` | *Length* | Length of the version string in bytes. |
| 8 | `byte[n]` | *Version* | UTF-16BE string (e.g., "1.0/Serato Scratch Live..."). |

### 2. Track Entries (`oent`)

Following the header, the file contains multiple track entries. Each entry represents a track played during the session.

**Entry Container:**

| Offset (Rel) | Type | Value (String) | Description |
| :--- | :--- | :--- | :--- |
| 0 | `char[4]` | `oent` | Magic marker for a session entry ("Object Entry"?). |
| 4 | `int32` | *Length* | Total length of the entry data following this integer. |
| 8 | `byte[n]` | *Data* | The content of the entry. |

**Entry Data (`adat`):**

Inside the `oent` data payload, there is an `adat` block containing the actual fields.

| Offset (Rel) | Type | Value (String) | Description |
| :--- | :--- | :--- | :--- |
| *var* | `char[4]` | `adat` | Magic marker for the data block. |
| *+4* | `int32` | *Length* | Length of the data block. |
| *+8* | `byte[n]` | *Fields* | Sequence of Field ID / Length / Value triplets. |

### 3. Fields

Inside the `adat` block, data is stored as triplets:

1. **Field ID** (4 bytes, int32)
2. **Length** (4 bytes, int32)
3. **Value** (Variable bytes)

#### Known Field IDs

| ID (Hex) | ID (Dec) | Data Type | Description |
| :--- | :--- | :--- | :--- |
| `0x02` | 2 | `String` | **Filepath**. Full path to the audio file. May contain trailing null bytes (`\0`). |
| `0x06` | 6 | `String` | **Title**. Track title. |
| `0x07` | 7 | `String` | **Artist**. Track artist. |
| `0x09` | 9 | `String` | **Genre**. Track genre. |
| `0x0F` | 15 | `int32` | **BPM**. Beats Per Minute. |
| `0x11` | 17 | `String` | **Key**. Musical key. |
| `0x1C` | 28 | `int32` | **Start Time**. Unix timestamp (unsigned). |
| `0x1D` | 29 | `int32` | **End Time**. Unix timestamp (unsigned). |
| `0x3F` | 63 | `String` | **Deck**. The deck used (e.g., "1", "2"). |

## Parsing Logic (Pseudocode)

```python
def read_session(file_handle):
    # 1. Read Header
    magic = read_bytes(4) # Expect "vrsn"
    v_len = read_int32()
    version = read_utf16be(v_len)

    # 2. logical loop
    while has_more_data():
        # Find next "oent" marker
        if match_marker("oent"):
            entry_len = read_int32()
            entry_data = read_bytes(entry_len)
            parse_entry(entry_data)

def parse_entry(data):
    # Find "adat" marker within the entry data
    if match_marker(data, "adat"):
        adat_len = read_int32(data)
        
        cursor = 0
        while cursor < adat_len:
            field_id = read_int32_at(cursor)
            field_len = read_int32_at(cursor + 4)
            field_val = read_bytes_at(cursor + 8, field_len)
            
            process_field(field_id, field_val)
            cursor += (8 + field_len)
```

## Important Notes

1. **Alignment/Padding**: The file format seems to be packed without strict alignment requirements between fields, relying on length headers.
2. **String Termination**: Path strings (`0x02`) often contain trailing null bytes (`0x00`) in UTF-16BE. When modifying paths, it is critical to preserve the exact number of null bytes if you wish to maintain binary fidelity, although strictly speaking they might just be padding. The `session-fixer` implementation attempts to preserve them.
3. **Timestamps**: Start and End times are standard Unix timestamps (seconds since Epoch).
4. **Unknown Fields**: Generators should be prepared to skip unknown field IDs by using the length parameter.

## Reference Implementation

See `session-fixer/src/session_fixer_parser.java` for a working Java implementation including:

* Reading and parsing entries
* Rebuilding length headers when modifying data (e.g. changing file paths)
* Handling trailing nulls in paths
