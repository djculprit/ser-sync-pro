import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Scans Serato database V2 to select which entry to fix when duplicates exist.
 * Based on user preference (keep-oldest or keep-newest by "date added").
 * 
 * This is a standalone utility that does NOT modify the existing
 * cdd_sync_database.java.
 */
public class cdd_sync_database_entry_selector {

    /**
     * Holds a database entry with path and date added.
     */
    private static class DbEntry {
        String path;
        long dateAdded; // Unix timestamp from uadd field

        DbEntry(String path, long dateAdded) {
            this.path = path;
            this.dateAdded = dateAdded;
        }
    }

    /**
     * Gets the database path for a track based on date preference.
     * Scans the database V2 file directly to find all entries matching the
     * filename.
     * 
     * @param databasePath Path to the database V2 file
     * @param filename     Filename to search for (case-insensitive)
     * @param keepNewest   If true, return newest entry; if false, return oldest
     * @return Path from database, or null if not found
     */
    public static String getPathByDatePreference(String databasePath, String filename, boolean keepNewest) {
        File dbFile = new File(databasePath);
        if (!dbFile.exists()) {
            return null;
        }

        List<DbEntry> entries = new ArrayList<>();
        String lowerFilename = filename.toLowerCase();

        try {
            byte[] data = cdd_sync_binary_utils.readFile(dbFile);
            scanForEntries(data, lowerFilename, entries);
        } catch (IOException e) {
            cdd_sync_log.error("Error scanning database V2: " + e.getMessage());
            return null;
        }

        if (entries.isEmpty()) {
            return null;
        }

        if (entries.size() == 1) {
            return entries.get(0).path;
        }

        // Select based on preference
        DbEntry selected = entries.get(0);
        for (DbEntry entry : entries) {
            if (keepNewest) {
                if (entry.dateAdded > selected.dateAdded) {
                    selected = entry;
                }
            } else {
                if (entry.dateAdded < selected.dateAdded) {
                    selected = entry;
                }
            }
        }

        return selected.path;
    }

    /**
     * Scans the database binary data for entries matching the filename.
     */
    private static void scanForEntries(byte[] data, String lowerFilename, List<DbEntry> entries) {
        byte[] otrkMarker = "otrk".getBytes(StandardCharsets.US_ASCII);

        int pos = 0;
        while (pos < data.length - 8) {
            // Look for otrk marker
            if (data[pos] == otrkMarker[0] && data[pos + 1] == otrkMarker[1] &&
                    data[pos + 2] == otrkMarker[2] && data[pos + 3] == otrkMarker[3]) {

                // Read record length
                int recordLen = cdd_sync_binary_utils.readInt(data, pos + 4);
                if (pos + 8 + recordLen > data.length) {
                    break;
                }

                // Parse this otrk record
                parseOtrkRecord(data, pos + 8, recordLen, lowerFilename, entries);
                pos += 8 + recordLen;
            } else {
                pos++;
            }
        }
    }

    /**
     * Parses a single otrk record for pfil and uadd fields.
     */
    private static void parseOtrkRecord(byte[] data, int start, int len,
            String lowerFilename, List<DbEntry> entries) {
        String path = null;
        long dateAdded = 0;

        int pos = start;
        int end = start + len;

        while (pos + 8 < end) {
            String tag = new String(data, pos, 4, StandardCharsets.US_ASCII);
            int fieldLen = cdd_sync_binary_utils.readInt(data, pos + 4);
            pos += 8;

            if (pos + fieldLen > end) {
                break;
            }

            if ("pfil".equals(tag)) {
                try {
                    path = new String(data, pos, fieldLen, StandardCharsets.UTF_16BE);
                } catch (Exception e) {
                    path = null;
                }
            }

            if ("uadd".equals(tag) && fieldLen == 4) {
                dateAdded = cdd_sync_binary_utils.readInt(data, pos) & 0xFFFFFFFFL;
            }

            pos += fieldLen;
        }

        // Check if this entry matches our filename
        if (path != null) {
            String entryFilename = cdd_sync_binary_utils.getFilename(path);
            if (entryFilename.equals(lowerFilename)) {
                entries.add(new DbEntry(path, dateAdded));
            }
        }
    }

}
