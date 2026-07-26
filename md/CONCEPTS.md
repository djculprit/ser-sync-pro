# Concepts & Glossary — cdd-sync-pro

Quick reference for domain-specific terms used in this project.

---

| Term | Definition |
|------|------------|
| **Crate** | A Serato playlist stored as a `.crate` binary file in `_Serato_/Subcrates/`. |
| **Database V2** | Serato's main track library index file (`_Serato_/database V2`). Uses a TLV binary format with UTF-16BE strings. |
| **Session file** | A `.session` binary file in `_Serato_/History/Sessions/` recording play history. Not to be confused with **Session log** below. |
| **Session log** | A timestamped `.log` file this tool writes for one sync/session-fixer run, at `<parent of _Serato_>/cdd-sync-pro/logs/`. Unrelated to Serato's own **Session file** (play history) — the naming overlap is coincidental. |
| **`_Serato_` folder** | The hidden folder Serato creates on each drive it scans. Contains database, crates, history, and settings. |
| **NFC / NFD** | Unicode normalization forms. **NFC** (precomposed) is used by most systems; **NFD** (decomposed) is used by macOS HFS+/APFS and Serato's database. |
| **TLV** | Tag-Length-Value — the binary encoding pattern Serato uses. Each field has a 4-byte ASCII tag, 4-byte length, and N bytes of data. |
| **UTF-16BE** | Big-Endian UTF-16 encoding — the string format Serato uses in all binary files. |
| **`pfil`** | The TLV tag for a track's file path inside `database V2` and `.crate` files. |
| **`otrk`** | The TLV tag marking a track record block in `database V2`. |
| **`oent`** | The TLV tag marking an entry block in `.session` files. |
| **`neworder.pref`** | A Serato config file that controls the display order of crates in the sidebar. |
| **Parent Crate** | An optional crate under which all synced folders appear as subcrates. |
| **Smart Crate Writing** | Optimization that reads existing crate content before writing; skips write if unchanged. |
| **Track Index** | In-memory lookup (`cdd_sync_track_index`) combining database + crate data for deduplication. |
| **Dupe Mover** | Feature that detects duplicate files on disk and moves copies to a timestamped safety folder. |
