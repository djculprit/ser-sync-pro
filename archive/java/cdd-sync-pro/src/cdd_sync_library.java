import java.io.File;
import java.util.*;

/**
 * Builds and manages Serato crate library.
 * Maps filesystem structure to crate hierarchy.
 */
public class cdd_sync_library {

    private Map<cdd_sync_crate, String> crateFileName = new HashMap<>();
    private cdd_sync_crate root;
    private List<cdd_sync_crate> crates = new ArrayList<>();
    private List<cdd_sync_crate> subCrates = new ArrayList<>();
    private cdd_sync_track_index trackIndex;

    public static cdd_sync_library createFrom(cdd_sync_media_library fsLibrary) {
        return createFrom(fsLibrary, null, null);
    }

    public static cdd_sync_library createFrom(cdd_sync_media_library fsLibrary, String parentCratePath) {
        return createFrom(fsLibrary, parentCratePath, null);
    }

    public static cdd_sync_library createFrom(cdd_sync_media_library fsLibrary, String parentCratePath,
            cdd_sync_track_index index) {
        cdd_sync_library result = new cdd_sync_library();
        result.trackIndex = index;

        // If parentCratePath is specified, use it as prefix for all crate names
        String initialCrateName = (parentCratePath != null) ? parentCratePath : "";
        result.buildLibrary(fsLibrary, 0, initialCrateName, false);

        return result;
    }

    private SortedSet<String> buildLibrary(cdd_sync_media_library fsLibrary, int level, String crateName,
            boolean includeSubcrateTracks) {
        SortedSet<String> all = new TreeSet<String>();

        // Add tracks from current directory
        all.addAll(fsLibrary.getTracks());

        // Build for every sub-directory
        for (cdd_sync_media_library child : fsLibrary.getChildren()) {
            String crateNameNext = crateName.length() > 0 ? crateName + "%%" + child.getDirectory()
                    : child.getDirectory();
            SortedSet<String> children = buildLibrary(child, level + 1, crateNameNext, includeSubcrateTracks);

            if (includeSubcrateTracks) {
                all.addAll(children);
            }
        }

        cdd_sync_crate crate = new cdd_sync_crate();

        // Set database reference for path encoding lookup
        if (trackIndex != null && trackIndex.getDatabase() != null) {
            crate.setDatabase(trackIndex.getDatabase());
        }

        // Add all tracks to the crate (crates are just path references).
        crate.addTracks(all);

        if (level == 0) {
            root = crate;
        } else if (level == 1) {
            crates.add(crate);
        } else {
            subCrates.add(crate);
        }
        crateFileName.put(crate, crateName + ".crate");

        return all;
    }

    public void writeTo(String seratoLibraryPath, boolean clearLibraryBeforeSync) throws cdd_sync_exception {
        if (clearLibraryBeforeSync) {
            cdd_sync_file_utils.deleteAllFilesInDirectory(seratoLibraryPath + "/Crates");
            cdd_sync_file_utils.deleteAllFilesInDirectory(seratoLibraryPath + "/Subcrates");
            cdd_sync_file_utils.deleteFile(seratoLibraryPath + "/database V2");
        }

        int total = crates.size() + subCrates.size();
        int current = 0;
        int updatedCount = 0;
        int skippedCount = 0;

        // Write parent crates
        for (cdd_sync_crate crate : crates) {
            current++;
            cdd_sync_log.progress("Processing crates", current, total);
            if (writeCrateSmart(crate, seratoLibraryPath, crateFileName.get(crate))) {
                updatedCount++;
            } else {
                skippedCount++;
            }
        }

        // Write sub-crates
        for (cdd_sync_crate crate : subCrates) {
            current++;
            cdd_sync_log.progress("Processing crates", current, total);
            if (writeCrateSmart(crate, seratoLibraryPath, crateFileName.get(crate))) {
                updatedCount++;
            } else {
                skippedCount++;
            }
        }

        cdd_sync_log.progressComplete("Writing crates");

        if (updatedCount > 0) {
            cdd_sync_log.info("Updated " + updatedCount + " crates (Skipped " + skippedCount + " unchanged).");
        } else {
            cdd_sync_log.info("No crate files needed updating (Skipped " + skippedCount + " unchanged).");
        }
    }

    /**
     * Writes crate to disk only if content has changed.
     * Returns true if written, false if skipped.
     */
    private boolean writeCrateSmart(cdd_sync_crate crate, String seratoLibraryPath, String fileName)
            throws cdd_sync_exception {
        File crateFile = new File(seratoLibraryPath + "/Subcrates/" + fileName);

        // Check if exists and matches
        if (crateFile.exists()) {
            try {
                cdd_sync_crate existing = cdd_sync_crate.readFrom(crateFile);
                if (existing.equals(crate)) {
                    cdd_sync_log.fix("[CRATE SKIPPED] " + fileName
                            + " (" + crate.getTrackCount() + " tracks, unchanged)");
                    return false; // No change needed
                }
            } catch (Exception e) {
                // If we can't read the existing one (corrupt, old version?), force update
            }
        }

        try {
            crateFile.getParentFile().mkdirs();
            crate.writeTo(crateFile);
            cdd_sync_log.fix("[CRATE WRITTEN] " + fileName
                    + " (" + crate.getTrackCount() + " tracks)");
            return true;
        } catch (cdd_sync_exception e) {
            throw new cdd_sync_exception("Error serializing crate '" + fileName + "'", e);
        }
    }

    public int getTotalNumberOfCrates() {
        return crates.size();
    }

    public int getTotalNumberOfSubCrates() {
        return subCrates.size();
    }

    public int getTotalTracksWritten() {
        int total = 0;
        for (cdd_sync_crate crate : crates) {
            total += crate.getTrackCount();
        }
        for (cdd_sync_crate crate : subCrates) {
            total += crate.getTrackCount();
        }
        return total;
    }

    /**
     * Returns all crate names (without .crate extension) for use in neworder.pref
     * generation.
     */
    public List<String> getAllCrateNames() {
        List<String> names = new ArrayList<>();
        for (cdd_sync_crate crate : crates) {
            String fileName = crateFileName.get(crate);
            if (fileName != null && fileName.endsWith(".crate")) {
                names.add(fileName.substring(0, fileName.length() - 6));
            }
        }
        for (cdd_sync_crate crate : subCrates) {
            String fileName = crateFileName.get(crate);
            if (fileName != null && fileName.endsWith(".crate")) {
                names.add(fileName.substring(0, fileName.length() - 6));
            }
        }
        return names;
    }

}
