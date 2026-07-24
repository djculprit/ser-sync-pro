import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Fixes broken filepaths in Serato's database V2 file.
 * Updates pfil (path) tags within otrk (track) blocks.
 */
public class cdd_sync_database_fixer {

    /**
     * Updates a path in the database V2 file.
     * 
     * @param databasePath Path to the database V2 file
     * @param oldPath      The broken path to find
     * @param newPath      The corrected path to replace with
     * @return true if the path was found and updated
     */
    public static boolean updatePath(String databasePath, String oldPath, String newPath) {
        File dbFile = new File(databasePath);
        if (!dbFile.exists()) {
            return false;
        }

        try {
            // Read entire file
            byte[] data = cdd_sync_binary_utils.readFile(dbFile);

            // Convert paths to UTF-16BE for searching
            byte[] oldPathBytes = oldPath.getBytes(StandardCharsets.UTF_16BE);
            byte[] newPathBytes = newPath.getBytes(StandardCharsets.UTF_16BE);

            // Build otrk index for efficient parent lookup
            List<int[]> otrkIndex = indexOtrkBlocks(data);

            // Find and replace the path
            byte[] result = replacePfilPath(data, oldPathBytes, newPathBytes, otrkIndex);

            if (result != null) {
                // Write back to file
                cdd_sync_binary_utils.writeFile(dbFile, result);
                return true;
            }

            return false;
        } catch (IOException e) {
            cdd_sync_log.error("Error updating database V2: " + e.getMessage());
            return false;
        }
    }

    /**
     * Batch updates multiple paths in the database V2 file.
     * More efficient than calling updatePath multiple times.
     * 
     * @param databasePath Path to the database V2 file
     * @param pathMappings Map of old paths to new paths
     * @return Number of paths successfully updated
     */
    public static int updatePaths(String databasePath, Map<String, String> pathMappings) {
        File dbFile = new File(databasePath);
        if (!dbFile.exists()) {
            return 0;
        }

        try {
            // Read entire file
            byte[] data = cdd_sync_binary_utils.readFile(dbFile);
            int updatedCount = 0;
            int totalPaths = pathMappings.size();
            int processed = 0;
            int progressStep = Math.max(1, totalPaths / 20); // 5% increments
            int nextProgressAt = progressStep;

            // Build otrk index once for efficient parent lookup
            List<int[]> otrkIndex = indexOtrkBlocks(data);

            for (Map.Entry<String, String> entry : pathMappings.entrySet()) {
                processed++;
                if (processed >= nextProgressAt) {
                    cdd_sync_log.progress("Updating database V2", processed, totalPaths);
                    nextProgressAt += progressStep;
                }

                // Normalize paths to match database format (relative, no volume prefix)
                String oldPath = normalizePathForDatabase(entry.getKey());
                String newPath = normalizePathForDatabase(entry.getValue());

                byte[] oldPathBytes = oldPath.getBytes(StandardCharsets.UTF_16BE);
                byte[] newPathBytes = newPath.getBytes(StandardCharsets.UTF_16BE);

                byte[] result = replacePfilPath(data, oldPathBytes, newPathBytes, otrkIndex);
                if (result != null) {
                    data = result;
                    updatedCount++;
                    // Rebuild index if data array size changed (path lengths differ)
                    if (oldPathBytes.length != newPathBytes.length) {
                        otrkIndex = indexOtrkBlocks(data);
                    }
                }
            }

            cdd_sync_log.progressComplete("Updating database V2");

            if (updatedCount > 0) {
                cdd_sync_binary_utils.writeFile(dbFile, data);
            }

            return updatedCount;
        } catch (IOException e) {
            cdd_sync_log.error("Error updating database V2: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Normalizes a path for database format (relative, no volume prefix).
     * Delegates to shared utility for consistent behavior.
     */
    private static String normalizePathForDatabase(String path) {
        return cdd_sync_binary_utils.normalizePathForDatabase(path);
    }

    /**
     * Builds an index of otrk block positions for fast parent lookup.
     * Returns a list of [otrkPos, otrkEnd] pairs.
     */
    private static List<int[]> indexOtrkBlocks(byte[] data) {
        List<int[]> blocks = new ArrayList<>();
        byte[] marker = "otrk".getBytes(StandardCharsets.US_ASCII);
        int pos = 0;
        while (pos < data.length - 8) {
            if (data[pos] == marker[0] && data[pos + 1] == marker[1] &&
                    data[pos + 2] == marker[2] && data[pos + 3] == marker[3]) {
                int len = cdd_sync_binary_utils.readInt(data, pos + 4);
                blocks.add(new int[] { pos, pos + 8 + len });
                pos = pos + 8 + len;
            } else {
                pos++;
            }
        }
        return blocks;
    }

    /**
     * Finds a pfil tag containing the old path and replaces it with the new path.
     * Adjusts length fields for both pfil and parent otrk block.
     * Uses pre-built otrk index for O(K) parent lookup instead of O(N) scanning.
     */
    private static byte[] replacePfilPath(byte[] data, byte[] oldPathBytes, byte[] newPathBytes,
            List<int[]> otrkIndex) {
        // Search for "pfil" tag followed by the old path
        byte[] pfilMarker = "pfil".getBytes(StandardCharsets.US_ASCII);

        int pos = 0;
        while (pos < data.length - 8 - oldPathBytes.length) {
            // Look for "pfil" marker
            if (data[pos] == pfilMarker[0] && data[pos + 1] == pfilMarker[1] &&
                    data[pos + 2] == pfilMarker[2] && data[pos + 3] == pfilMarker[3]) {

                // Read pfil length (4 bytes, big-endian)
                int pfilLen = cdd_sync_binary_utils.readInt(data, pos + 4);

                // Check if this pfil contains our old path
                int pathStart = pos + 8;
                if (pathStart + pfilLen <= data.length && pfilLen == oldPathBytes.length) {
                    boolean match = true;
                    for (int i = 0; i < oldPathBytes.length; i++) {
                        if (data[pathStart + i] != oldPathBytes[i]) {
                            match = false;
                            break;
                        }
                    }

                    if (match) {
                        // Found the path! Now we need to:
                        // 1. Find the parent otrk block
                        // 2. Calculate length difference
                        // 3. Rebuild the data with new path

                        int lengthDiff = newPathBytes.length - oldPathBytes.length;

                        // Find the otrk block that contains this pfil using index
                        int otrkPos = findParentOtrk(otrkIndex, pos);
                        if (otrkPos == -1) {
                            // Can't find parent otrk, just replace inline if same length
                            if (lengthDiff == 0) {
                                System.arraycopy(newPathBytes, 0, data, pathStart, newPathBytes.length);
                                return data;
                            }
                            pos++;
                            continue;
                        }

                        // Read otrk length
                        int otrkLen = cdd_sync_binary_utils.readInt(data, otrkPos + 4);

                        // Build new data array
                        byte[] newData = new byte[data.length + lengthDiff];

                        // Copy everything before the pfil content
                        System.arraycopy(data, 0, newData, 0, pathStart);

                        // Update pfil length
                        int newPfilLen = newPathBytes.length;
                        newData[pos + 4] = (byte) ((newPfilLen >> 24) & 0xFF);
                        newData[pos + 5] = (byte) ((newPfilLen >> 16) & 0xFF);
                        newData[pos + 6] = (byte) ((newPfilLen >> 8) & 0xFF);
                        newData[pos + 7] = (byte) (newPfilLen & 0xFF);

                        // Copy new path
                        System.arraycopy(newPathBytes, 0, newData, pathStart, newPathBytes.length);

                        // Copy everything after the old path
                        int afterOldPath = pathStart + oldPathBytes.length;
                        System.arraycopy(data, afterOldPath, newData, pathStart + newPathBytes.length,
                                data.length - afterOldPath);

                        // Update otrk length
                        int newOtrkLen = otrkLen + lengthDiff;
                        newData[otrkPos + 4] = (byte) ((newOtrkLen >> 24) & 0xFF);
                        newData[otrkPos + 5] = (byte) ((newOtrkLen >> 16) & 0xFF);
                        newData[otrkPos + 6] = (byte) ((newOtrkLen >> 8) & 0xFF);
                        newData[otrkPos + 7] = (byte) (newOtrkLen & 0xFF);

                        return newData;
                    }
                }
            }
            pos++;
        }

        return null; // Path not found
    }

    /**
     * Finds the otrk block that contains the given position using pre-built index.
     */
    private static int findParentOtrk(List<int[]> otrkBlocks, int targetPos) {
        for (int[] block : otrkBlocks) {
            if (targetPos >= block[0] && targetPos < block[1]) {
                return block[0];
            }
        }
        return -1;
    }

}
