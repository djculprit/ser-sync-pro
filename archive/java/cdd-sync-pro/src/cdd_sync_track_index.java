import java.io.File;

/**
 * Loads the Serato database V2 and exposes it for path encoding lookups.
 * Used by cdd_sync_library to ensure crate track paths match the exact
 * byte encoding Serato used when it originally indexed the files.
 */
public class cdd_sync_track_index {

    private cdd_sync_database database;

    /**
     * Loads the Serato database V2 from the given _Serato_ folder.
     *
     * @param seratoPath Path to the _Serato_ folder
     * @return cdd_sync_track_index instance (database may be null if not found)
     */
    public static cdd_sync_track_index createFrom(String seratoPath) {
        cdd_sync_track_index index = new cdd_sync_track_index();

        String dbPath = seratoPath + "/database V2";
        if (new File(dbPath).exists()) {
            cdd_sync_log.info("Loading database V2 for path encoding...");
            index.database = cdd_sync_database.readFrom(dbPath);
            if (index.database != null) {
                cdd_sync_log.info("Found " + index.database.getTrackCount() + " tracks in database V2");
            }
        }

        return index;
    }

    /**
     * Returns the database reference for path encoding lookup.
     */
    public cdd_sync_database getDatabase() {
        return database;
    }
}
