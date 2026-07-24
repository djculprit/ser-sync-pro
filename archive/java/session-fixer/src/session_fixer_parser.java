import java.io.*;
import java.util.*;

/**
 * Represents a single Serato session file.
 * Handles reading and writing .session binary files from History/Sessions.
 * 
 * Session files use the same binary format as .crate files:
 * - UTF-16BE encoded strings
 * - "oent" markers for track entries (instead of "otrk")
 * - "adat" blocks with field ID / length / value triplets
 */
public class session_fixer_parser {

    private String version;
    private List<SessionEntry> entries = new ArrayList<>();
    private byte[] rawData; // Keep original bytes for reconstruction

    /**
     * Represents a single track entry in a session.
     */
    public static class SessionEntry {
        public int offset; // Position in file
        public int length; // Entry length
        public String filepath; // Field 0x02
        public String title; // Field 0x06
        public String artist; // Field 0x07
        public String genre; // Field 0x09
        public int bpm; // Field 0x0F
        public String key; // Field 0x11
        public long startTime; // Field 0x1C (Unix timestamp)
        public long endTime; // Field 0x1D (Unix timestamp)
        public String deck; // Field 0x3F
        public byte[] rawEntry; // Original bytes for this entry
    }

    // Field IDs used in session files
    private static final int FIELD_FILEPATH = 0x02;
    private static final int FIELD_TITLE = 0x06;
    private static final int FIELD_ARTIST = 0x07;
    private static final int FIELD_GENRE = 0x09;
    private static final int FIELD_BPM = 0x0F;
    private static final int FIELD_KEY = 0x11;
    private static final int FIELD_START_TIME = 0x1C;
    private static final int FIELD_END_TIME = 0x1D;
    private static final int FIELD_DECK = 0x3F;

    public session_fixer_parser() {
    }

    public String getVersion() {
        return version;
    }

    public List<SessionEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int getEntryCount() {
        return entries.size();
    }

    /**
     * Gets all unique file paths from session entries.
     * Strips null bytes from paths.
     */
    public Set<String> getUniquePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (SessionEntry entry : entries) {
            if (entry.filepath != null && !entry.filepath.isEmpty()) {
                // Strip null bytes
                String cleanPath = entry.filepath.replace("\u0000", "");
                paths.add(cleanPath);
            }
        }
        return paths;
    }

    /**
     * Calculates session duration from first track start to last track end.
     * 
     * @return Duration in seconds, or 0 if unable to calculate
     */
    public int getSessionDurationSeconds() {
        if (entries.isEmpty()) {
            return 0;
        }

        long minStart = Long.MAX_VALUE;
        long maxEnd = 0;

        for (SessionEntry entry : entries) {
            if (entry.startTime > 0 && entry.startTime < minStart) {
                minStart = entry.startTime;
            }
            if (entry.endTime > maxEnd) {
                maxEnd = entry.endTime;
            }
        }

        if (minStart == Long.MAX_VALUE || maxEnd == 0 || maxEnd <= minStart) {
            return 0;
        }

        return (int) (maxEnd - minStart);
    }

    /**
     * Reads session from file.
     */
    public static session_fixer_parser readFrom(File inFile) throws cdd_sync_exception {
        session_fixer_parser result = new session_fixer_parser();

        try {
            // Read entire file into memory for parsing and reconstruction
            byte[] data;
            try (FileInputStream fis = new FileInputStream(inFile)) {
                data = fis.readAllBytes();
            }
            result.rawData = data;

            // Parse header: "vrsn" + 4-byte length + UTF-16BE version string
            if (data.length < 8) {
                throw new cdd_sync_exception("Session file too small");
            }

            String magic = new String(data, 0, 4);
            if (!"vrsn".equals(magic)) {
                throw new cdd_sync_exception("Invalid session file: missing vrsn header");
            }

            int versionLen = cdd_sync_binary_utils.readInt(data, 4);
            result.version = readUTF16BE(data, 8, versionLen);

            // Find and parse all "oent" entries
            int pos = 0;
            while (pos < data.length - 4) {
                int idx = indexOf(data, "oent".getBytes(), pos);
                if (idx < 0)
                    break;

                SessionEntry entry = parseEntry(data, idx);
                if (entry != null) {
                    result.entries.add(entry);
                }
                pos = idx + 4;
            }

        } catch (IOException e) {
            throw new cdd_sync_exception("Failed to read session file: " + inFile.getName(), e);
        }

        return result;
    }

    /**
     * Parses a single entry starting at the given offset.
     */
    private static SessionEntry parseEntry(byte[] data, int oentOffset) {
        try {
            SessionEntry entry = new SessionEntry();
            entry.offset = oentOffset;

            // Read entry length (4 bytes after "oent")
            if (oentOffset + 8 > data.length)
                return null;
            entry.length = cdd_sync_binary_utils.readInt(data, oentOffset + 4);

            // Save raw bytes for this entry
            int entryEnd = Math.min(oentOffset + 8 + entry.length, data.length);
            entry.rawEntry = Arrays.copyOfRange(data, oentOffset, entryEnd);

            // Look for "adat" marker
            int adatOffset = indexOf(data, "adat".getBytes(), oentOffset, entryEnd);
            if (adatOffset < 0)
                return entry;

            int adatLen = cdd_sync_binary_utils.readInt(data, adatOffset + 4);
            int fieldPos = adatOffset + 8;
            int fieldEnd = Math.min(adatOffset + 8 + adatLen, entryEnd);

            // Parse field ID / length / value triplets
            while (fieldPos < fieldEnd - 8) {
                int fieldId = cdd_sync_binary_utils.readInt(data, fieldPos);
                int fieldLen = cdd_sync_binary_utils.readInt(data, fieldPos + 4);
                fieldPos += 8;

                if (fieldLen < 0 || fieldLen > 1024 || fieldPos + fieldLen > fieldEnd) {
                    break;
                }

                switch (fieldId) {
                    case FIELD_FILEPATH:
                        entry.filepath = readUTF16BE(data, fieldPos, fieldLen);
                        break;
                    case FIELD_TITLE:
                        entry.title = readUTF16BE(data, fieldPos, fieldLen);
                        break;
                    case FIELD_ARTIST:
                        entry.artist = readUTF16BE(data, fieldPos, fieldLen);
                        break;
                    case FIELD_GENRE:
                        entry.genre = readUTF16BE(data, fieldPos, fieldLen);
                        break;
                    case FIELD_BPM:
                        if (fieldLen == 4) {
                            entry.bpm = cdd_sync_binary_utils.readInt(data, fieldPos);
                        }
                        break;
                    case FIELD_KEY:
                        entry.key = readUTF16BE(data, fieldPos, fieldLen);
                        break;
                    case FIELD_START_TIME:
                        if (fieldLen == 4) {
                            entry.startTime = cdd_sync_binary_utils.readInt(data, fieldPos) & 0xFFFFFFFFL;
                        }
                        break;
                    case FIELD_END_TIME:
                        if (fieldLen == 4) {
                            entry.endTime = cdd_sync_binary_utils.readInt(data, fieldPos) & 0xFFFFFFFFL;
                        }
                        break;
                    case FIELD_DECK:
                        entry.deck = readUTF16BE(data, fieldPos, fieldLen);
                        break;
                }
                fieldPos += fieldLen;
            }

            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Updates a file path in the session data.
     * Replaces all occurrences of oldPath with newPath.
     * 
     * @return Number of replacements made
     */
    public int updatePath(String oldPath, String newPath) {
        if (rawData == null || oldPath == null || newPath == null) {
            return 0;
        }

        // Strip null bytes from paths for consistent comparison
        oldPath = oldPath.replace("\u0000", "");
        newPath = newPath.replace("\u0000", "");

        // Convert paths to UTF-16BE bytes
        byte[] oldBytes = toUTF16BE(oldPath);

        int replacements = 0;
        int pos = 0;

        // Count occurrences of oldBytes in rawData (segments are not needed —
        // rebuildWithUpdatedPaths re-parses from scratch)
        while (pos < rawData.length) {
            int idx = indexOf(rawData, oldBytes, pos);
            if (idx < 0) {
                break;
            }
            replacements++;
            pos = idx + oldBytes.length;
        }

        if (replacements > 0) {
            // Rebuild rawData with correct length fields
            rawData = rebuildWithUpdatedPaths(oldPath, newPath);

            // Update entries list
            for (SessionEntry entry : entries) {
                if (oldPath.equals(entry.filepath)) {
                    entry.filepath = newPath;
                }
            }
        }

        return replacements;
    }

    /**
     * Rebuilds the session file data with updated paths.
     * This properly updates length fields throughout the structure.
     */
    private byte[] rebuildWithUpdatedPaths(String oldPath, String newPath) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            int pos = 0;

            // Copy header (everything before first "oent")
            int firstOent = indexOf(rawData, "oent".getBytes(), 0);
            if (firstOent < 0) {
                return rawData; // No entries, return as-is
            }
            out.write(rawData, 0, firstOent);
            pos = firstOent;

            // Process each entry
            while (pos < rawData.length) {
                int oentPos = indexOf(rawData, "oent".getBytes(), pos);
                if (oentPos < 0) {
                    // Copy remaining data
                    out.write(rawData, pos, rawData.length - pos);
                    break;
                }

                // Copy any data between entries
                if (oentPos > pos) {
                    out.write(rawData, pos, oentPos - pos);
                }

                // Read entry length
                int entryLen = cdd_sync_binary_utils.readInt(rawData, oentPos + 4);
                int entryEnd = Math.min(oentPos + 8 + entryLen, rawData.length);

                // Extract entry data and check for path
                byte[] entryData = Arrays.copyOfRange(rawData, oentPos + 8, entryEnd);
                byte[] oldPathBytes = toUTF16BE(oldPath);

                if (indexOf(entryData, oldPathBytes, 0) >= 0) {
                    // This entry contains the old path - rebuild it
                    byte[] newEntryData = rebuildEntry(entryData, oldPath, newPath);

                    // Write "oent" marker
                    out.write("oent".getBytes());
                    // Write new length
                    writeInt(out, newEntryData.length);
                    // Write new entry data
                    out.write(newEntryData);
                } else {
                    // Entry unchanged - copy as-is
                    out.write(rawData, oentPos, entryEnd - oentPos);
                }

                pos = entryEnd;
            }

        } catch (IOException e) {
            return rawData; // Return original on error
        }

        return out.toByteArray();
    }

    /**
     * Rebuilds a single entry with an updated path.
     */
    private byte[] rebuildEntry(byte[] entryData, String oldPath, String newPath) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Find "adat" in entry
        int adatPos = indexOf(entryData, "adat".getBytes(), 0);
        if (adatPos < 0) {
            return entryData; // No adat block, return as-is
        }

        // Copy everything before adat
        out.write(entryData, 0, adatPos);

        // Read adat length
        int adatLen = cdd_sync_binary_utils.readInt(entryData, adatPos + 4);
        int adatEnd = Math.min(adatPos + 8 + adatLen, entryData.length);

        // Rebuild adat block with updated path
        ByteArrayOutputStream adatOut = new ByteArrayOutputStream();
        int fieldPos = adatPos + 8;

        while (fieldPos < adatEnd - 8) {
            int fieldId = cdd_sync_binary_utils.readInt(entryData, fieldPos);
            int fieldLen = cdd_sync_binary_utils.readInt(entryData, fieldPos + 4);

            if (fieldLen < 0 || fieldLen > 1024 || fieldPos + 8 + fieldLen > adatEnd) {
                break;
            }

            if (fieldId == FIELD_FILEPATH) {
                // Read the original path with its trailing nulls intact
                String currentPathRaw = readUTF16BE(entryData, fieldPos + 8, fieldLen);

                // Strip nulls for comparison only
                String currentPathClean = currentPathRaw.replace("\u0000", "");
                String cleanOldPath = oldPath.replace("\u0000", "");

                if (cleanOldPath.equals(currentPathClean)) {
                    // Count trailing nulls in original path so we preserve them
                    int trailingNulls = 0;
                    for (int i = currentPathRaw.length() - 1; i >= 0 && currentPathRaw.charAt(i) == '\u0000'; i--) {
                        trailingNulls++;
                    }

                    // Build new path with same trailing nulls
                    String cleanNewPath = newPath.replace("\u0000", "");
                    StringBuilder newPathWithNulls = new StringBuilder(cleanNewPath);
                    for (int i = 0; i < trailingNulls; i++) {
                        newPathWithNulls.append('\u0000');
                    }

                    // Write updated path with preserved trailing nulls
                    byte[] newPathBytes = toUTF16BE(newPathWithNulls.toString());
                    writeInt(adatOut, fieldId);
                    writeInt(adatOut, newPathBytes.length);
                    adatOut.write(newPathBytes);
                    fieldPos += 8 + fieldLen;
                    continue;
                }
            }

            // Copy field as-is
            writeInt(adatOut, fieldId);
            writeInt(adatOut, fieldLen);
            adatOut.write(entryData, fieldPos + 8, fieldLen);
            fieldPos += 8 + fieldLen;
        }

        // Write adat marker with new length
        out.write("adat".getBytes());
        byte[] newAdatData = adatOut.toByteArray();
        writeInt(out, newAdatData.length);
        out.write(newAdatData);

        // Copy anything after adat block
        if (adatEnd < entryData.length) {
            out.write(entryData, adatEnd, entryData.length - adatEnd);
        }

        return out.toByteArray();
    }

    /**
     * Writes session to file.
     */
    public void writeTo(File outFile) throws cdd_sync_exception {
        if (rawData == null) {
            throw new cdd_sync_exception("No session data to write");
        }

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(rawData);
        } catch (IOException e) {
            throw new cdd_sync_exception("Failed to write session file: " + outFile.getName(), e);
        }
    }

    // Helper methods for binary parsing

    private static String readUTF16BE(byte[] data, int offset, int length) {
        try {
            return new String(data, offset, length, "UTF-16BE");
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] toUTF16BE(String str) {
        try {
            return str.getBytes("UTF-16BE");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int indexOf(byte[] data, byte[] pattern, int start) {
        return indexOf(data, pattern, start, data.length);
    }

    private static int indexOf(byte[] data, byte[] pattern, int start, int end) {
        outer: for (int i = start; i <= end - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
