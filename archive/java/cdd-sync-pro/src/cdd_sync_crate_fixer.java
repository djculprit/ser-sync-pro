import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixes broken filepaths in existing Serato crates and database V2.
 *
 * Three independent, sequential steps:
 *   1. fixExistingCrates()   — reads .crate files, updates broken paths, writes
 *                              fixed crates. No database involvement.
 *   2. (caller writes new crates via cdd_sync_library)
 *   3. updateDatabasePaths() — reads database V2, updates broken pfil paths. Runs last.
 */
public class cdd_sync_crate_fixer {

    // =========================================================================
    // Step 2 — Fix existing .crate files (database V2 as source of truth)
    // =========================================================================
    /**
     * Scans every .crate file in _Serato_/Subcrates and updates track paths
     * using the scanned media library (filesystem) as the source of truth.
     *
     * For each track in each crate: look up its filename in the library index.
     * If the library holds a different (unambiguous) path for that filename,
     * replace the crate's stored path with the normalized library path.
     * Crates that had no changes are left untouched.
     *
     * When {@code database} is non-null, the resolved path is further refined
     * via {@link cdd_sync_binary_utils#resolveSeratoPath} so the written path
     * uses Serato's exact original filename encoding (NFD-preserved). This
     * prevents Serato from creating orphaned duplicate entries in database V2
     * when it reads crates written with filesystem-sourced NFC paths.
     * If database is null, falls back to the raw filesystem path.
     *
     * Covers ALL crates including hand-curated Live sets.
     * If multiple library paths share the same filename, the first match is used.
     * Uses setTracksRaw() so dedup never removes a valid track.
     *
     * @param seratoPath Path to the _Serato_ folder
     * @param library    Scanned media library (filesystem source of truth)
     * @param database   Parsed Serato database V2 for exact encoding lookup;
     *                   may be null (falls back to filesystem path)
     */
    public static void fixExistingCrates(String seratoPath, cdd_sync_media_library library,
            cdd_sync_database database) {
        cdd_sync_log.info("Step 2: Rewriting crate paths from filesystem...");

        if (library == null) {
            cdd_sync_log.info("No media library — skipping Step 2.");
            return;
        }

        // Build filename -> list of absolute paths from the live filesystem.
        // List<String> lets us detect filename collisions across folders.
        Map<String, List<String>> libIndex = buildLibraryIndex(library);

        if (libIndex.isEmpty()) {
            cdd_sync_log.info("Media library is empty — skipping Step 2.");
            return;
        }

        // Collect ALL .crate files from both Crates/ and Subcrates/ (recursive).
        // Serato uses Crates/ for top-level crates and Subcrates/ for nested ones;
        // some hand-curated crates may live in either directory.
        List<File> crateFiles = new ArrayList<>();
        collectCrateFiles(new File(seratoPath + "/Crates"),    crateFiles);
        collectCrateFiles(new File(seratoPath + "/Subcrates"), crateFiles);

        if (crateFiles.isEmpty()) {
            cdd_sync_log.info("No crate files found in Crates/ or Subcrates/.");
            return;
        }

        cdd_sync_log.info("Step 2: found " + crateFiles.size() + " crate files to inspect.");

        int fixedCrates = 0;
        int fixedPaths  = 0;

        for (File crateFile : crateFiles) {  // crateFiles is now a List<File>
            cdd_sync_crate crate;
            try {
                crate = cdd_sync_crate.readFrom(crateFile);
            } catch (cdd_sync_exception e) {
                cdd_sync_log.error("Failed to read crate: " + crateFile.getName());
                continue;
            }

            List<String> original = new ArrayList<>(crate.getTracks());
            List<String> updated  = new ArrayList<>(original.size());
            boolean changed = false;

            // Debug: log track count for this crate to diagnose skipped crates.
            cdd_sync_log.step2("[DEBUG] " + crateFile.getName()
                    + " — loaded " + original.size() + " tracks from disk.");

            int hitCount = 0, missCount = 0, sameCount = 0;

            for (String trackPath : original) {
                String filename = cdd_sync_binary_utils.getFilename(trackPath);
                List<String> candidates = libIndex.get(filename);

                if (candidates != null && !candidates.isEmpty()) {
                    // Prefer Serato's exact filename encoding from database V2 (NFD-preserved).
                    // Falls back to the raw filesystem path when database is null or has no entry.
                    String resolvedPath = cdd_sync_binary_utils
                            .resolveSeratoPath(candidates.get(0), database);
                    // Convert absolute path to the relative form crates expect.
                    String newRelPath = cdd_sync_binary_utils
                            .normalizePathForDatabase(resolvedPath);
                    if (!newRelPath.equals(trackPath)) {
                        cdd_sync_log.step2("[PATH FIX] " + crateFile.getName()
                                + " | OLD: " + trackPath
                                + " -> NEW: " + newRelPath);
                        updated.add(newRelPath);
                        changed = true;
                        fixedPaths++;
                        hitCount++;
                    } else {
                        updated.add(trackPath); // Path already correct.
                        sameCount++;
                    }
                } else {
                    // File not found in library — keep existing path unchanged.
                    updated.add(trackPath);
                    missCount++;
                }
            }

            cdd_sync_log.step2("[DEBUG] " + crateFile.getName()
                    + " — fixed=" + hitCount + " same=" + sameCount + " notFound=" + missCount);


            if (changed) {
                // Mutate the already-read crate in place and write back —
                // same pattern as Steps 3 & 4. Avoids manually re-copying
                // version / sorting / sortingRev / columns from a scratch object.
                crate.setTracksRaw(updated);
                try {
                    crate.writeTo(crateFile);
                    fixedCrates++;
                    cdd_sync_log.fix("[CRATE FIXED] " + crateFile.getName()
                            + " (" + crate.getTrackCount() + " tracks)");
                } catch (cdd_sync_exception e) {
                    cdd_sync_log.error("Failed to write crate: " + crateFile.getName());
                }
            }
        }

        cdd_sync_log.info("Step 2 complete: " + fixedPaths + " paths fixed across "
                + fixedCrates + " crates.");
    }

    // =========================================================================
    // Step 1 — Update database V2 paths (runs before crate fixes)
    // =========================================================================

    /**
     * Reads the Serato database V2, checks each stored track path against the
     * filesystem, and updates any broken paths by filename lookup.
     * This runs LAST — after fixExistingCrates() and new crate writing are done.
     *
     * @param seratoPath Path to the _Serato_ folder
     * @param library    Scanned media library for filename lookups
     */
    public static void updateDatabasePaths(String seratoPath, cdd_sync_media_library library) {
        cdd_sync_log.info("Step 1: Updating broken paths in database V2...");

        if (library == null) {
            cdd_sync_log.info("No media library — skipping Step 1.");
            return;
        }

        String databasePath = seratoPath + "/database V2";
        cdd_sync_database database = cdd_sync_database.readFrom(databasePath);
        if (database == null) {
            cdd_sync_log.error("No Serato database V2 found at: " + seratoPath);
            return;
        }

        String volumeRoot = getVolumeRoot(seratoPath);
        Map<String, List<String>> libraryFiles = buildLibraryIndex(library);

        if (libraryFiles.isEmpty()) {
            cdd_sync_log.info("Media library is empty — skipping Step 1.");
            return;
        }

        if (volumeRoot == null) {
            cdd_sync_log.step1("[WARN] Cannot derive volume root from seratoPath: " + seratoPath
                    + " — file existence checks skipped; resolving by filename only.");
        }

        // Walk every path in the database, find broken ones, collect fixes.
        // Use LinkedHashMap to preserve insertion order for deterministic output.
        Map<String, String> pathFixes = new LinkedHashMap<>();

        for (String dbTrackPath : database.getAllTrackPaths()) {
            if (volumeRoot != null) {
                String absolutePath = volumeRoot + "/" + dbTrackPath;
                if (new File(absolutePath).exists()) {
                    continue; // Still valid — skip.
                }
            }

            String filename = cdd_sync_binary_utils.getFilename(dbTrackPath);
            List<String> candidates = libraryFiles.get(filename);

            if (candidates == null || candidates.isEmpty()) {
                // Can't resolve — leave the database entry as-is.
                continue;
            }
            if (candidates.size() > 1) {
                cdd_sync_log.step1("[DB AMBIGUOUS] " + candidates.size()
                        + " candidates for: " + filename);
                continue;
            }

            String fixedRelative = cdd_sync_binary_utils
                    .normalizePathForDatabase(candidates.get(0));

            cdd_sync_log.step1("[DB FIX] BROKEN: " + dbTrackPath
                    + " -> FIXED: " + fixedRelative);

            pathFixes.put(dbTrackPath, fixedRelative);
        }

        if (pathFixes.isEmpty()) {
            cdd_sync_log.info("No broken paths found in database V2.");
            return;
        }

        cdd_sync_log.info("Updating database V2 with " + pathFixes.size() + " path fixes...");
        int updated = cdd_sync_database_fixer.updatePaths(databasePath, pathFixes);
        cdd_sync_log.info("Updated " + updated + " paths in database V2.");
    }

    // =========================================================================
    // Backward-compatible facade (used by runFixPaths and any legacy callers)
    // =========================================================================

    /**
     * Facade over the two independent fix steps (steps 1 & 2 only).
     * Kept for backward compatibility with runFixPaths().
     * Step 2 now uses the filesystem library directly — no database reload needed.
     */
    public static void fixBrokenPaths(String seratoPath, cdd_sync_media_library library,
            cdd_sync_database database, String dupeMoveMode) {
        updateDatabasePaths(seratoPath, library);
        fixExistingCrates(seratoPath, library, database);
    }

    // =========================================================================
    // Step 3 — Append new tracks to existing crates matching library filepath
    // =========================================================================

    /**
     * For each folder-path crate that ALREADY EXISTS on disk, appends any new
     * tracks from the corresponding media library folder. Uses addTrack()
     * (filename-based dedup) so tracks already present are never duplicated.
     *
     * @param seratoPath      Path to the _Serato_ folder
     * @param library         Scanned media library
     * @param parentCratePath Crate prefix (e.g. "Current"), or null for none
     * @param trackIndex      Track index with database reference (may be null)
     */
    public static void appendNewTracksToMatchingCrates(String seratoPath,
            cdd_sync_media_library library, String parentCratePath,
            cdd_sync_track_index trackIndex) {
        cdd_sync_log.info("Step 3: Appending new tracks to existing crates...");

        File subcratesDir = new File(seratoPath + "/Subcrates");
        if (!subcratesDir.exists()) {
            cdd_sync_log.info("No Subcrates directory found, skipping append step.");
            return;
        }

        Map<String, List<String>> crateMap = buildCrateFileMap(library, parentCratePath);
        cdd_sync_database database = (trackIndex != null) ? trackIndex.getDatabase() : null;

        int appendedCount = 0;
        int skippedCount = 0;

        for (Map.Entry<String, List<String>> entry : crateMap.entrySet()) {
            String crateFileName = entry.getKey();
            List<String> newTracks = entry.getValue();
            File crateFile = new File(subcratesDir, crateFileName);

            if (!crateFile.exists()) {
                continue; // Step 4 will create it.
            }

            cdd_sync_crate crate;
            try {
                crate = cdd_sync_crate.readFrom(crateFile);
            } catch (cdd_sync_exception e) {
                cdd_sync_log.error("Failed to read crate for append: " + crateFileName);
                continue;
            }

            int before = crate.getTrackCount();
            if (database != null) {
                crate.setDatabase(database);
            }
            for (String track : newTracks) {
                crate.addTrack(track);
            }
            int after = crate.getTrackCount();

            if (after > before) {
                try {
                    crate.writeTo(crateFile);
                    cdd_sync_log.step3("[CRATE APPENDED] " + crateFileName
                            + " (+" + (after - before) + " tracks, total " + after + ")");
                    appendedCount++;
                } catch (cdd_sync_exception e) {
                    cdd_sync_log.error("Failed to write appended crate: " + crateFileName);
                }
            } else {
                cdd_sync_log.step3("[CRATE APPEND SKIPPED] " + crateFileName
                        + " (" + before + " tracks, no new tracks)");
                skippedCount++;
            }
        }

        if (appendedCount > 0) {
            cdd_sync_log.info("Step 3 complete: " + appendedCount + " crates updated.");
        } else {
            cdd_sync_log.info("Step 3 complete: no new tracks to append ("
                    + skippedCount + " unchanged).");
        }
    }

    // =========================================================================
    // Step 4 — Create new crates for new library paths (never overwrite)
    // =========================================================================

    /**
     * For each folder-path crate that does NOT yet exist on disk, creates and
     * writes a new .crate file populated from that folder. Existing crates are
     * always skipped — Step 3 handles them.
     *
     * @param seratoPath      Path to the _Serato_ folder
     * @param library         Scanned media library
     * @param parentCratePath Crate prefix (e.g. "Current"), or null for none
     * @param trackIndex      Track index with database reference (may be null)
     */
    public static void createNewCrates(String seratoPath,
            cdd_sync_media_library library, String parentCratePath,
            cdd_sync_track_index trackIndex) {
        cdd_sync_log.info("Step 4: Creating new crates for new library paths...");

        File subcratesDir = new File(seratoPath + "/Subcrates");
        Map<String, List<String>> crateMap = buildCrateFileMap(library, parentCratePath);
        cdd_sync_database database = (trackIndex != null) ? trackIndex.getDatabase() : null;

        int createdCount = 0;
        int skippedCount = 0;

        for (Map.Entry<String, List<String>> entry : crateMap.entrySet()) {
            String crateFileName = entry.getKey();
            List<String> tracks = entry.getValue();
            File crateFile = new File(subcratesDir, crateFileName);

            if (crateFile.exists()) {
                cdd_sync_log.step4("[CRATE EXISTS, SKIPPED] " + crateFileName);
                skippedCount++;
                continue;
            }

            cdd_sync_crate crate = new cdd_sync_crate();
            if (database != null) {
                crate.setDatabase(database);
            }
            crate.addTracks(tracks);

            try {
                crateFile.getParentFile().mkdirs();
                crate.writeTo(crateFile);
                cdd_sync_log.step4("[CRATE CREATED] " + crateFileName
                        + " (" + crate.getTrackCount() + " tracks)");
                createdCount++;
            } catch (cdd_sync_exception e) {
                cdd_sync_log.error("Failed to write new crate: " + crateFileName);
            }
        }

        if (createdCount > 0) {
            cdd_sync_log.info("Step 4 complete: " + createdCount + " new crates created ("
                    + skippedCount + " existing skipped).");
        } else {
            cdd_sync_log.info("Step 4 complete: no new crates needed ("
                    + skippedCount + " existing skipped).");
        }
    }

    // =========================================================================
    // Crate-name <-> folder mapping helpers (Steps 3 & 4)
    // =========================================================================

    /**
     * Builds a map of crate filename -> direct track paths for every folder
     * in the media library (level 1+). Mirrors the %%‑joined naming convention
     * used by cdd_sync_library. Level 0 (root) is skipped.
     */
    private static Map<String, List<String>> buildCrateFileMap(
            cdd_sync_media_library library, String parentCratePath) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String rootName = (parentCratePath != null && !parentCratePath.isEmpty())
                ? parentCratePath : "";
        buildCrateFileMapRecursive(library, rootName, 0, result);
        return result;
    }

    /** Recursive walker for buildCrateFileMap. */
    private static void buildCrateFileMapRecursive(cdd_sync_media_library dir,
            String crateName, int level, Map<String, List<String>> result) {
        if (level > 0) {
            result.put(crateName + ".crate", new ArrayList<>(dir.getTracks()));
        }
        for (cdd_sync_media_library child : dir.getChildren()) {
            String childName = crateName.isEmpty()
                    ? child.getDirectory()
                    : crateName + "%%" + child.getDirectory();
            buildCrateFileMapRecursive(child, childName, level + 1, result);
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /**
     * Recursively collects all .crate files under the given directory into result.
     * Silently skips directories that don't exist.
     */
    private static void collectCrateFiles(File dir, List<File> result) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectCrateFiles(entry, result);
            } else if (entry.getName().endsWith(".crate")) {
                result.add(entry);
            }
        }
    }

    /**
     * Returns the volume root from a standard _Serato_ path.
     * e.g. /Volumes/Current/_Serato_ -> /Volumes/Current
     * Returns null if the path does not end in _Serato_ — callers must handle null.
     */
    private static String getVolumeRoot(String seratoPath) {
        File seratoDir = new File(seratoPath);
        if (seratoDir.getName().equalsIgnoreCase("_Serato_")) {
            return seratoDir.getParent();
        }
        return null; // Non-standard path — cannot derive volume root safely.
    }

    /**
     * Builds filename (lowercase NFC) -> list of absolute paths from the library.
     * A list is used instead of a single value to detect filename collisions across
     * folders, which would make safe resolution impossible.
     */
    private static Map<String, List<String>> buildLibraryIndex(cdd_sync_media_library library) {
        Map<String, List<String>> index = new HashMap<>();
        List<String> allTracks = new ArrayList<>();
        library.flattenTracks(allTracks);
        for (String path : allTracks) {
            String filename = cdd_sync_binary_utils.getFilename(path);
            index.computeIfAbsent(filename, k -> new ArrayList<>()).add(path);
        }
        return index;
    }

}
