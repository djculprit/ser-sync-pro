import java.io.*;
import java.text.Normalizer;

/**
 * Shared binary utility methods used across cdd-sync-pro and session-fixer.
 * Consolidates duplicated readInt, readFile, writeFile, getFilename, and
 * formatSize methods.
 */
public class cdd_sync_binary_utils {

    /**
     * Reads a 4-byte big-endian integer from a byte array.
     */
    public static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
                ((data[offset + 1] & 0xFF) << 16) |
                ((data[offset + 2] & 0xFF) << 8) |
                (data[offset + 3] & 0xFF);
    }

    /**
     * Reads entire file into byte array.
     */
    public static byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read != data.length) {
                throw new IOException("Could not read entire file");
            }
            return data;
        }
    }

    /**
     * Writes byte array to file.
     */
    public static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    /**
     * Extracts filename from path with Unicode NFC normalization and lowercase.
     */
    public static String getFilename(String path) {
        if (path == null)
            return "";
        // Apply NFC normalization for consistent Unicode handling
        path = Normalizer.normalize(path, Normalizer.Form.NFC);
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1).toLowerCase() : path.toLowerCase();
    }

    /**
     * Extracts raw filename from path WITHOUT any normalization.
     * Preserves exact Serato encoding for later matching.
     */
    public static String getRawFilename(String path) {
        if (path == null)
            return "";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Formats byte count as human-readable size.
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * Normalizes a path for comparison (lowercase, NFC, strips volume/drive
     * prefix).
     * Use this when comparing paths from different sources (database vs
     * filesystem).
     */
    public static String normalizePath(String path) {
        if (path == null)
            return "";
        path = Normalizer.normalize(path, Normalizer.Form.NFC);
        path = path.toLowerCase();
        path = path.replace('\\', '/');
        path = path.replaceAll("^[a-z]:/", "");
        path = path.replaceAll("^/volumes/[^/]+/", "");
        return path;
    }

    /**
     * Normalizes a path for Serato database format (relative, no volume prefix).
     * Does NOT lowercase — preserves original case for database writes.
     */
    public static String normalizePathForDatabase(String path) {
        if (path == null)
            return "";
        path = path.replace('\\', '/');
        path = path.replaceAll("^[a-zA-Z]:/", "");
        path = path.replaceAll("^/Volumes/[^/]+/", "");
        return path;
    }

    /**
     * Resolves a track path using Serato's original filename encoding from the
     * database.
     * If the database has a Serato-encoded filename for this track, replaces the
     * filesystem filename with the Serato version (preserving encoding for crate
     * writes).
     * Returns the original trackPath unchanged if db is null or no match found.
     */
    public static String resolveSeratoPath(String trackPath, cdd_sync_database db) {
        if (db == null) {
            return trackPath;
        }
        String seratoFilename = db.getSeratoFilename(trackPath);
        if (seratoFilename == null) {
            return trackPath;
        }
        java.io.File f = new java.io.File(trackPath);
        String dir = f.getParent();
        if (dir == null) {
            return trackPath;
        }
        return dir + java.io.File.separator + seratoFilename;
    }
}
