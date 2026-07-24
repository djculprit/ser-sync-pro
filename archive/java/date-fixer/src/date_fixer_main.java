/**
 * Serato Date Fixer
 *
 * Standalone application that reads Serato database V2 for each track's
 * uadd Unix timestamp and writes it to the macOS kMDItemDateAdded xattr,
 * making Finder's "Date Added" column match Serato's "Added" column.
 *
 * Usage: java -jar date-fixer.jar [--dry-run]
 *
 * Configuration: date-fixer.properties
 */
public class date_fixer_main {

    public static void main(String[] args) {
        boolean cliDryRun = false;
        for (String arg : args) {
            if ("--dry-run".equals(arg)) cliDryRun = true;
        }

        date_fixer_config config = null;
        try {
            config = new date_fixer_config();
        } catch (Exception e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            System.err.println("Make sure date-fixer.properties exists in the current directory.");
            System.exit(1);
        }

        if (config.isGuiMode()) {
            cdd_sync_log.setMode(true);
        } else {
            cdd_sync_log.setMode(false);
        }

        final boolean dryRun = cliDryRun || config.isDryRun();

        try {
            cdd_sync_log.info("=== Serato Date Fixer ===");
            if (dryRun) cdd_sync_log.info("[DRY RUN MODE — no files will be modified]");
            cdd_sync_log.info("");

            String seratoPath       = config.getSeratoPath();
            String musicLibraryPath = config.getMusicLibraryPath();

            // Validate paths
            java.io.File seratoDir = new java.io.File(seratoPath);
            if (!seratoDir.exists()) {
                cdd_sync_log.error("Serato folder not found: " + seratoPath);
                cdd_sync_log.fatalError();
            }

            java.io.File dbFile = new java.io.File(seratoPath + "/database V2");
            if (!dbFile.exists()) {
                cdd_sync_log.error("database V2 not found in: " + seratoPath);
                cdd_sync_log.fatalError();
            }

            java.io.File musicDir = new java.io.File(musicLibraryPath);
            if (!musicDir.exists()) {
                cdd_sync_log.error("Music library not found: " + musicLibraryPath);
                cdd_sync_log.fatalError();
            }

            cdd_sync_log.info("Serato path   : " + seratoPath);
            cdd_sync_log.info("Music library : " + musicLibraryPath);
            cdd_sync_log.info("");

            // Run core logic
            date_fixer_core_logic.run(seratoPath, musicLibraryPath,
                                      dryRun, config.isWriteDateCreatedEnabled());

            cdd_sync_log.info("");
            cdd_sync_log.info("=== Date Fixer Complete ===");

            if (config.isGuiMode()) {
                cdd_sync_log.info("");
                cdd_sync_log.info("You may close this window.");
            }
        } catch (cdd_sync_fatal_exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }
}
