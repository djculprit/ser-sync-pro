import java.io.*;
import java.util.*;

/**
 * Parses Serato database V2 file to extract existing track information.
 * Used for deduplication during sync.
 */
public class cdd_sync_database {

    // Track info storage: path -> size
    private Map<String, String> tracksByPath = new HashMap<>();
    private Map<String, String> tracksByFilename = new HashMap<>();
    // Additional index: just filename (lowercase, NFC normalized) -> original path
    // for O(1) lookup
    private Map<String, String> tracksByFilenameOnly = new HashMap<>();
    // Raw filename index: normalized lookup key -> raw filename (preserving Serato
    // encoding)
    private Map<String, String> rawFilenamesByNormalizedName = new HashMap<>();

    private int trackCount = 0;

    /**
     * Reads and parses the database V2 file.
     * 
     * @param databasePath Path to the database V2 file
     * @return cdd_sync_database instance with parsed tracks, or null if file
     *         doesn't
     *         exist
     */
    public static cdd_sync_database readFrom(String databasePath) {
        File dbFile = new File(databasePath);
        if (!dbFile.exists()) {
            return null;
        }

        cdd_sync_database db = new cdd_sync_database();

        try {
            db.parseDatabase(dbFile);
        } catch (Exception e) {
            cdd_sync_log.error("Error parsing database V2: " + e.getMessage());
            return null;
        }

        return db;
    }

    /**
     * Parses the binary database V2 file.
     */
    private void parseDatabase(File dbFile) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(dbFile)));

        try {
            // Skip header: vrsn + version string
            skipHeader(in);

            // Read track records
            while (in.available() > 0) {
                try {
                    readTrackRecord(in);
                } catch (EOFException e) {
                    break;
                }
            }
        } finally {
            in.close();
        }
    }

    /**
     * Skips the database header.
     */
    private void skipHeader(DataInputStream in) throws IOException {
        // Read "vrsn"
        byte[] vrsn = new byte[4];
        in.readFully(vrsn);

        if (!"vrsn".equals(new String(vrsn))) {
            throw new IOException("Invalid database V2 format: missing vrsn header");
        }

        // Read length (2 bytes null + 2 bytes length)
        in.skipBytes(2);
        int headerLen = in.readUnsignedShort();

        // Skip the header string
        in.skipBytes(headerLen);
    }

    /**
     * Reads a single track record (otrk block).
     */
    private void readTrackRecord(DataInputStream in) throws IOException {
        // Look for "otrk" marker
        byte[] marker = new byte[4];
        in.readFully(marker);
        String markerStr = new String(marker);

        if (!"otrk".equals(markerStr)) {
            // Not a track record, skip this byte and try again
            // This handles any padding or unknown blocks
            return;
        }

        // Read record length
        int recordLen = in.readInt();

        // Read the record data
        byte[] recordData = new byte[recordLen];
        in.readFully(recordData);

        // Parse the record for pfil and tsiz tags
        parseTrackData(recordData);
    }

    /**
     * Parses track data to extract path and size.
     */
    private void parseTrackData(byte[] data) {
        String path = null;
        String size = null;

        int pos = 0;
        while (pos + 8 < data.length) {
            // Read tag (4 bytes)
            String tag = new String(data, pos, 4);
            pos += 4;

            // Read length (4 bytes, big-endian)
            int len = cdd_sync_binary_utils.readInt(data, pos);
            pos += 4;

            if (pos + len > data.length) {
                break;
            }

            // Extract path (pfil tag)
            if ("pfil".equals(tag)) {
                try {
                    path = new String(data, pos, len, "UTF-16BE");
                } catch (UnsupportedEncodingException e) {
                    // Fallback
                    path = new String(data, pos, len);
                }
            }

            // Extract size (tsiz tag)
            if ("tsiz".equals(tag)) {
                try {
                    size = new String(data, pos, len, "UTF-16BE");
                } catch (UnsupportedEncodingException e) {
                    size = new String(data, pos, len);
                }
            }

            pos += len;
        }

        if (path != null) {
            String normalizedPath = normalizePath(path);
            String key = normalizedPath + (size != null ? "|" + size : "");

            tracksByPath.put(key, path);

            // Also index by filename
            String filename = cdd_sync_binary_utils.getFilename(path);
            String filenameKey = filename + (size != null ? "|" + size : "");
            tracksByFilename.put(filenameKey, path);
            // Index by filename only (for fast path lookup without size)
            tracksByFilenameOnly.put(filename, path);

            // Store raw filename (preserving exact encoding) for matching later
            String rawFilename = cdd_sync_binary_utils.getRawFilename(path);
            rawFilenamesByNormalizedName.put(filename, rawFilename);

            trackCount++;
        }
    }

    /**
     * Normalizes a path for comparison.
     * Delegates to shared utility for consistent behavior.
     */
    private static String normalizePath(String path) {
        return cdd_sync_binary_utils.normalizePath(path);
    }

    /**
     * Checks if a track exists using path-based matching.
     * 
     * @param trackPath Full path to the track
     * @param fileSize  File size (can be null)
     * @return true if track already exists
     */
    public boolean containsTrackByPath(String trackPath, String fileSize) {
        String normalizedPath = normalizePath(trackPath);
        String key = normalizedPath + (fileSize != null ? "|" + fileSize : "");
        return tracksByPath.containsKey(key);
    }

    /**
     * Checks if a track exists using filename-based matching.
     * 
     * @param trackPath Full path to the track (filename extracted)
     * @param fileSize  File size (can be null)
     * @return true if a track with same filename exists
     */
    public boolean containsTrackByFilename(String trackPath, String fileSize) {
        String filename = cdd_sync_binary_utils.getFilename(trackPath);
        String key = filename + (fileSize != null ? "|" + fileSize : "");
        return tracksByFilename.containsKey(key);
    }

    /**
     * Returns total number of tracks indexed.
     */
    public int getTrackCount() {
        return trackCount;
    }

    /**
     * Returns all track paths stored in the database (relative, no volume prefix).
     * Used to iterate and check each path against the live filesystem.
     *
     * Uses tracksByPath (keyed by normalizedPath+size) so every unique track is
     * returned — including tracks that share filenames across different folders.
     * tracksByFilenameOnly is intentionally NOT used here because it only holds
     * one path per filename, silently dropping the rest.
     */
    public List<String> getAllTrackPaths() {
        return new ArrayList<>(tracksByPath.values());
    }

    /**
     * Gets the original database path for a track by filename.
     * This returns the EXACT path encoding from the database.
     * 
     * @param trackPath Path from filesystem
     * @return Original path from database, or null if not found
     */
    public String getOriginalPathByFilename(String trackPath) {
        String filename = cdd_sync_binary_utils.getFilename(trackPath);
        // O(1) lookup using the filename-only index
        return tracksByFilenameOnly.get(filename);
    }

    /**
     * Gets the original filename with Serato's exact encoding.
     * Use this when writing to crates to match existing database entries.
     * 
     * @param trackPath Path from filesystem (to match by filename)
     * @return Original filename from database (preserving encoding), or null if not
     *         found
     */
    public String getSeratoFilename(String trackPath) {
        String normalizedKey = cdd_sync_binary_utils.getFilename(trackPath);
        return rawFilenamesByNormalizedName.get(normalizedKey);
    }
}
