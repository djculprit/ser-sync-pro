import java.io.File;
import java.io.FilenameFilter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans and fixes broken file paths in Serato session files.
 * Operates on History/Sessions/*.session files.
 * 
 * Supports scanning multiple music libraries in order - first match wins.
 * Logs unfixable paths for user review.
 */
public class session_fixer_core_logic {

    /**
     * Fixes broken paths in all .session files in the given Serato directory.
     * Checks multiple music libraries in order - first match wins.
     * Updates the database V2 file first to prevent duplicates, then updates
     * sessions.
     * 
     * @param seratoPath Path to the _Serato_ folder (in home Music directory)
     * @param libraries  List of scanned media libraries to check (in order)
     * @param database   The Serato database for path normalization (may be null)
     */
    public static void fixBrokenPaths(String seratoPath, List<cdd_sync_media_library> libraries,
            cdd_sync_database database) {
        cdd_sync_log.info("Checking for broken filepaths in session files...");

        // 1. Build a map of filename -> absolute path from ALL media libraries
        // Use normalized filenames (NFC) as keys to handle macOS NFD encoding
        // First library takes priority if same filename exists in multiple
        Map<String, String> libraryFiles = new HashMap<>();
        int totalTracks = 0;

        for (int i = libraries.size() - 1; i >= 0; i--) {
            // Process in reverse order so first library wins on duplicates
            cdd_sync_media_library library = libraries.get(i);
            List<String> tracks = new ArrayList<>();
            library.flattenTracks(tracks);

            for (String path : tracks) {
                String normalizedKey = cdd_sync_binary_utils.getFilename(path);
                libraryFiles.put(normalizedKey, path);
            }
            totalTracks += tracks.size();
        }

        cdd_sync_log.info("Loaded " + totalTracks + " tracks from " + libraries.size() + " libraries for lookup");

        // 2. Find session files
        File sessionsDir = new File(seratoPath + "/History/Sessions");
        if (!sessionsDir.exists() || !sessionsDir.isDirectory()) {
            cdd_sync_log.error("Sessions directory not found: " + sessionsDir.getAbsolutePath());
            cdd_sync_log.error("Make sure you're pointing to the _Serato_ folder in ~/Music/");
            return;
        }

        File[] sessionFiles = sessionsDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".session");
            }
        });

        if (sessionFiles == null || sessionFiles.length == 0) {
            cdd_sync_log.info("No session files found.");
            return;
        }

        cdd_sync_log.info("Found " + sessionFiles.length + " session files to scan.");

        // 3. Collect all path fixes needed and track unfixable paths
        Map<String, String> pathFixes = new HashMap<>(); // old path -> new path
        Set<String> unfixablePaths = new HashSet<>(); // paths we couldn't fix
        Set<String> alreadyChecked = new HashSet<>(); // avoid duplicate logging
        int totalBrokenPaths = 0;

        for (File sessionFile : sessionFiles) {
            session_fixer_parser session;

            try {
                session = session_fixer_parser.readFrom(sessionFile);
            } catch (cdd_sync_exception e) {
                cdd_sync_log.error("Failed to read session: " + sessionFile.getName());
                continue;
            }

            // Check each unique path in the session
            Set<String> uniquePaths = session.getUniquePaths();
            for (String trackPath : uniquePaths) {
                // Strip null bytes from path (session files may have trailing nulls)
                trackPath = trackPath.replace("\u0000", "");

                // Skip if we already processed this path
                if (alreadyChecked.contains(trackPath)) {
                    continue;
                }
                alreadyChecked.add(trackPath);

                // Check if file exists at original path
                File trackFile = new File(trackPath);
                if (!trackFile.exists()) {
                    totalBrokenPaths++;

                    // File is missing, try to find it in our library map
                    String normalizedKey = cdd_sync_binary_utils.getFilename(trackPath);
                    String fixedPath = libraryFiles.get(normalizedKey);

                    if (fixedPath != null && new File(fixedPath).exists()) {
                        // Found a fix!
                        String normalizedPath = fixedPath;

                        // Check if database has an existing path for this file
                        if (database != null) {
                            String dbPath = database.getOriginalPathByFilename(fixedPath);
                            if (dbPath != null && new File(dbPath).exists()) {
                                normalizedPath = dbPath;
                            } else if (dbPath != null) {
                                pathFixes.put(dbPath, fixedPath);
                                normalizedPath = fixedPath;
                            }
                        }

                        pathFixes.put(trackPath, normalizedPath);

                        cdd_sync_log.info("Found fix for broken path:");
                        cdd_sync_log.info("  Broken: " + trackPath);
                        cdd_sync_log.info("   Fixed: " + normalizedPath);
                    } else {
                        // Could not find in any library - log and leave as-is
                        unfixablePaths.add(trackPath);
                    }
                }
            }
        }

        // 4. Report unfixable paths
        if (!unfixablePaths.isEmpty()) {
            cdd_sync_log.info("");
            cdd_sync_log.info("=== Unfixable Paths (not found in any library) ===");
            cdd_sync_log.info("These files may have been intentionally removed.");
            cdd_sync_log.info("Leaving " + unfixablePaths.size() + " broken paths unchanged:");
            for (String path : unfixablePaths) {
                cdd_sync_log.info("  - " + path);
            }
            cdd_sync_log.info("");
        }

        // Summary
        cdd_sync_log.info("Broken paths found: " + totalBrokenPaths);
        cdd_sync_log.info("  - Fixable: " + pathFixes.size());
        cdd_sync_log.info("  - Unfixable (left as-is): " + unfixablePaths.size());

        if (pathFixes.isEmpty()) {
            cdd_sync_log.info("No broken paths could be fixed.");
            return;
        }

        // 5. Update database V2 file with all path fixes first
        String databasePath = seratoPath + "/database V2";
        int dbUpdated = cdd_sync_database_fixer.updatePaths(databasePath, pathFixes);
        if (dbUpdated > 0) {
            cdd_sync_log.info("Updated " + dbUpdated + " paths in database V2");
        }

        // 6. Now update all session files using parallel processing
        final AtomicInteger totalFixedSessions = new AtomicInteger(0);
        final AtomicInteger totalFixedEntries = new AtomicInteger(0);
        final AtomicInteger processedCount = new AtomicInteger(0);
        final int totalSessionFiles = sessionFiles.length;
        final Map<String, String> fixes = pathFixes; // Final reference for lambda

        cdd_sync_log.info("");
        cdd_sync_log.info("=== Updating Session Files (parallel) ===");

        // Use a thread pool with 4 threads for parallel processing
        int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (File sessionFile : sessionFiles) {
            executor.submit(() -> {
                int currentCount = processedCount.incrementAndGet();
                session_fixer_parser session;

                try {
                    session = session_fixer_parser.readFrom(sessionFile);
                } catch (cdd_sync_exception e) {
                    return;
                }

                int entriesFixed = 0;
                for (Map.Entry<String, String> fix : fixes.entrySet()) {
                    int replaced = session.updatePath(fix.getKey(), fix.getValue());
                    entriesFixed += replaced;
                }

                if (entriesFixed > 0) {
                    try {
                        session.writeTo(sessionFile);
                        totalFixedSessions.incrementAndGet();
                        totalFixedEntries.addAndGet(entriesFixed);
                        cdd_sync_log.info("[" + currentCount + "/" + totalSessionFiles + "] Fixed " + entriesFixed
                                + " paths in: " + sessionFile.getName());
                    } catch (cdd_sync_exception e) {
                        cdd_sync_log.error("[" + currentCount + "/" + totalSessionFiles + "] Failed to write: "
                                + sessionFile.getName());
                    }
                }
            });
        }

        // Wait for all tasks to complete
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            cdd_sync_log.error("Processing interrupted");
        }

        cdd_sync_log.info("");
        if (totalFixedSessions.get() > 0) {
            cdd_sync_log.info(
                    "Fixed " + totalFixedEntries.get() + " path entries across " + totalFixedSessions.get()
                            + " session files.");
        }
    }

    /**
     * Deletes sessions shorter than the minimum duration.
     * Also removes corresponding entries from history.database.
     * 
     * @param seratoPath     Path to _Serato_ folder
     * @param minDurationMin Minimum session duration in minutes
     * @return Number of sessions deleted
     */
    public static int deleteShortSessions(String seratoPath, int minDurationMin) {
        if (minDurationMin <= 0) {
            return 0;
        }

        cdd_sync_log.info("=== Session Duration Cleanup ===");
        cdd_sync_log.info("Deleting sessions shorter than " + minDurationMin + " minutes...");

        // Step 1: Find and delete short session files
        File sessionsDir = new File(seratoPath + "/History/Sessions");
        if (!sessionsDir.exists()) {
            cdd_sync_log.info("No Sessions directory found.");
            return 0;
        }

        File[] sessionFiles = sessionsDir.listFiles((dir, name) -> name.endsWith(".session"));
        if (sessionFiles == null || sessionFiles.length == 0) {
            cdd_sync_log.info("No session files found.");
            return 0;
        }

        Set<String> deletedSessionNames = new HashSet<>();
        int minDurationSec = minDurationMin * 60;

        for (File sessionFile : sessionFiles) {
            try {
                session_fixer_parser session = session_fixer_parser.readFrom(sessionFile);
                int durationSec = session.getSessionDurationSeconds();

                if (durationSec > 0 && durationSec < minDurationSec) {
                    int durationMin = durationSec / 60;
                    String filename = sessionFile.getName();
                    if (sessionFile.delete()) {
                        cdd_sync_log.info("  Deleted: " + filename + " (" + durationMin + " min)");
                        // Store the base name without .session extension for matching
                        deletedSessionNames.add(filename.replace(".session", ""));
                    }
                }
            } catch (cdd_sync_exception e) {
                cdd_sync_log.error("Skipping unreadable session: " + sessionFile.getName() + " — " + e.getMessage());
            } catch (Exception e) {
                cdd_sync_log
                        .error("Unexpected error reading session: " + sessionFile.getName() + " — " + e.getMessage());
            }
        }

        if (deletedSessionNames.isEmpty()) {
            cdd_sync_log.info("No sessions under " + minDurationMin + " minutes found.");
            cdd_sync_log.info("");
            return 0;
        }

        cdd_sync_log.info("Deleted " + deletedSessionNames.size() + " short session file(s).");

        // Step 2: Remove entries from history.database
        File historyDb = new File(seratoPath + "/History/history.database");
        if (!historyDb.exists()) {
            cdd_sync_log.info("");
            return deletedSessionNames.size();
        }

        try {
            byte[] data = java.nio.file.Files.readAllBytes(historyDb.toPath());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int pos = 0;
            int removedEntries = 0;

            // Copy header (vrsn block)
            if (data.length >= 8 && data[0] == 'v' && data[1] == 'r' && data[2] == 's' && data[3] == 'n') {
                int vrsnLen = cdd_sync_binary_utils.readInt(data, 4);
                int vrsnEnd = 8 + vrsnLen;
                out.write(data, 0, vrsnEnd);
                pos = vrsnEnd;
            }

            // Copy ocol blocks (column definitions) and filter oses blocks
            while (pos < data.length - 8) {
                // Check for ocol or oses markers
                if (pos + 4 <= data.length) {
                    String marker = new String(data, pos, 4);

                    if (marker.equals("ocol") || marker.equals("oses")) {
                        int blockLen = cdd_sync_binary_utils.readInt(data, pos + 4);
                        int blockEnd = Math.min(pos + 8 + blockLen, data.length);

                        if (marker.equals("oses")) {
                            // Check if this session should be removed
                            // Look for field 0x2D (duration) to match with deleted sessions
                            boolean shouldRemove = false;
                            int searchPos = pos + 8;

                            // Parse the oses entry to get its duration
                            int duration = 0;
                            while (searchPos < blockEnd - 8) {
                                if (data[searchPos] == 'a' && data[searchPos + 1] == 'd' &&
                                        data[searchPos + 2] == 'a' && data[searchPos + 3] == 't') {
                                    int adatLen = cdd_sync_binary_utils.readInt(data, searchPos + 4);
                                    int adatEnd = Math.min(searchPos + 8 + adatLen, blockEnd);
                                    int fPos = searchPos + 8;

                                    while (fPos < adatEnd - 8) {
                                        int fieldId = cdd_sync_binary_utils.readInt(data, fPos);
                                        int fieldLen = cdd_sync_binary_utils.readInt(data, fPos + 4);

                                        if (fieldLen < 0 || fieldLen > 4096 || fPos + 8 + fieldLen > adatEnd) {
                                            break;
                                        }

                                        if (fieldId == 0x2D && fieldLen == 4) {
                                            duration = cdd_sync_binary_utils.readInt(data, fPos + 8);
                                        }

                                        fPos += 8 + fieldLen;
                                    }
                                    break;
                                }
                                searchPos++;
                            }

                            // Remove if duration is below threshold
                            if (duration > 0 && duration < minDurationSec) {
                                shouldRemove = true;
                                removedEntries++;
                            }

                            if (!shouldRemove) {
                                out.write(data, pos, blockEnd - pos);
                            }
                        } else {
                            // Copy ocol blocks as-is
                            out.write(data, pos, blockEnd - pos);
                        }

                        pos = blockEnd;
                        continue;
                    }
                }
                pos++;
            }

            // Write updated history.database
            java.nio.file.Files.write(historyDb.toPath(), out.toByteArray());
            cdd_sync_log.info("Removed " + removedEntries + " entries from history.database.");

        } catch (Exception e) {
            cdd_sync_log.error("Failed to update history.database: " + e.getMessage());
        }

        cdd_sync_log.info("");
        return deletedSessionNames.size();
    }
}
