# Serato Session History Path Fixer

Standalone tool to fix broken file paths in Serato session history files.

## What It Does

1. **Backs up** your `_Serato_` folder before making changes
2. **Deletes short sessions** (optional, based on minimum duration)
3. **Scans** your music libraries to find relocated files
4. **Fixes** broken paths in `.session` files using parallel processing
5. **Logs** progress and unfixable paths

> [!WARNING]
> **Processing time may be significant** if you have multiple years of session files (200+ sessions with 100k+ track entries) to process. This is why the session fixer is a separate, standalone application—it's designed to be run once after migrating your music library, not as part of regular syncing.

## Quick Start

```bash
cd distr/session-fixer/
# Edit session-fixer.properties with your paths
java -jar session-fixer.jar
```

## Configuration

Edit `session-fixer.properties`:

```properties
# Mode: "cmd" for command-line, "gui" for GUI mode
mode = cmd

# Multiple music library paths (comma-separated)
# Checked in order - first match wins
music.library.filesystem = /Volumes/Current/Crates, /Volumes/Vault/Crates

# MUST be your HOME _Serato_ folder (where History/Sessions exists)
music.library.serato = ~/music/_Serato_

# Create backup before fixing (recommended!)
backup.enabled = true

# Delete sessions shorter than X minutes (leave blank to disable)
session.min.duration = 
```

### Important Notes

- **`music.library.serato`** must point to `~/Music/_Serato_` (your home directory), **not** an external drive.
- **Multiple paths** are checked sequentially. First match wins.
- **Unfixable paths** (files not found) are logged and left unchanged.
- **Log file**: All output saved to `session-fixer.log`.

## Session Cleanup (Optional)

Delete sessions shorter than a minimum duration:

```properties
# Delete sessions shorter than 5 minutes
session.min.duration = 5
```

- Leave blank or `0` to disable
- Runs **before** path fixing to save time
- Deleted sessions are logged

## Example Output

```text
=== Serato Session Path Fixer ===

Serato folder: /Users/you/Music/_Serato_
Music libraries (2 configured):
  1. /Volumes/Current/Crates [OK]
  2. /Volumes/Vault/Crates [OK]

Creating backup...
Backup complete (45.2 MB)

=== Session Duration Cleanup ===
Deleting sessions shorter than 5 minutes...
  Deleted: 12345.session (2 min)
  Deleted: 67890.session (1 min)
Deleted 2 short session(s).

Found 638 session files to scan.

Broken paths found: 12169
  - Fixable: 11538
  - Unfixable (left as-is): 631

=== Updating Session Files (parallel) ===
[1/638] Fixed 542 paths in: 43346.session
[4/638] Fixed 150 paths in: 65218.session
...

Fixed 166506 path entries across 539 session files.

=== Session Path Fixer Complete ===
```

## Building from Source

```bash
cd /path/to/cdd-sync-pro
ant session-fixer-jar
```

Output: `distr/session-fixer/session-fixer.jar`

## Technical Notes

- **Parallel processing**: Uses 4 threads for faster session file updates
- **UTF-16BE encoding**: Matches Serato's binary format
- **Trailing null bytes**: Preserved for binary alignment
- **Unicode normalization**: NFC handles macOS filename variations

---

## Session File Format Reference

### Binary Structure

```text
[vrsn]  4 bytes marker + 4 bytes length + UTF-16BE version string
[oent]  4 bytes marker + 4 bytes length + entry data (repeats per track)
  └─[adat]  4 bytes marker + 4 bytes length + field data
      └─ Field triplets: [4 bytes ID][4 bytes length][value]
```

### Field IDs (inside `adat` blocks)

| Field ID | Name            | Type           | Description                              |
|----------|-----------------|----------------|------------------------------------------|
| `0x01`   | Row Index       | int32          | Position in session (1, 2, 3...)         |
| `0x02`   | **File Path**   | UTF-16BE       | Full path to file (with trailing null)   |
| `0x06`   | Title           | UTF-16BE       | Track title                              |
| `0x07`   | Artist          | UTF-16BE       | Artist name                              |
| `0x08`   | Album           | UTF-16BE       | Album name                               |
| `0x09`   | Genre           | UTF-16BE       | Genre tag                                |
| `0x0F`   | BPM             | int32          | Beats per minute                         |
| `0x11`   | Key             | UTF-16BE       | Musical key (e.g., "10A", "Gm")          |
| `0x13`   | Grouping        | UTF-16BE       | Grouping tag                             |
| `0x14`   | Remixer         | UTF-16BE       | Remixer name                             |
| `0x15`   | Label           | UTF-16BE       | Record label                             |
| `0x16`   | Composer        | UTF-16BE       | Composer name                            |
| `0x17`   | Year            | UTF-16BE       | Release year                             |
| `0x1C`   | Start Time      | int32          | Unix timestamp (track started)           |
| `0x1D`   | End Time        | int32          | Unix timestamp (track stopped)           |
| `0x1F`   | Play Count      | int32          | Number of plays                          |
| `0x27`   | Added           | int8           | Flag: track was added                    |
| `0x2D`   | Duration        | int32          | Track duration in seconds                |
| `0x30`   | Deck Number     | int32          | Which deck played (1, 2, 3, 4)           |
| `0x32`   | Played          | int8           | Flag: played past threshold              |
| `0x33`   | Key (Alt)       | UTF-16BE       | Alternative key notation                 |
| `0x34`   | Missing         | int8           | Flag: file is missing/offline            |
| `0x35`   | Added Timestamp | int32          | When track was added to library          |
| `0x3F`   | Deck Name       | UTF-16BE       | "Offline", "Deck 1", "Deck 2", etc.      |

### Example: Reading a Track Entry

```java
// Inside adat block, fields repeat as: [ID:4][LEN:4][DATA:LEN]
int fieldId = readInt(data, pos);      // e.g., 0x00000002
int fieldLen = readInt(data, pos + 4); // e.g., 0x000000DC (220 bytes)
byte[] value = Arrays.copyOfRange(data, pos + 8, pos + 8 + fieldLen);

if (fieldId == 0x02) {
    String filepath = new String(value, StandardCharsets.UTF_16BE);
    // filepath = "/Volumes/Current/Crates/Song.mp3\0" (with trailing null)
}
```

## Related

- **cdd-sync-pro** - Main Serato crate sync tool (see [README.md](../README.md))
