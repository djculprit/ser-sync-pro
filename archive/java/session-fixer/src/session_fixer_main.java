import java.io.File;

/**
 * Serato Session Path Fixer
 * 
 * Standalone application that:
 * 1. Backs up the _Serato_ folder (optional)
 * 2. Scans session files for broken file paths
 * 3. Fixes broken paths by looking up files in the music library
 * 
 * Usage: java -jar session-fixer.jar
 * 
 * Configuration: session-fixer.properties
 */
public class session_fixer_main {

    public static void main(String[] args) {
        session_fixer_config config = null;

        try {
            config = new session_fixer_config();
        } catch (Exception e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            System.err.println("Make sure session-fixer.properties exists in the current directory.");
            System.exit(1);
        }

        // Initialize logging
        if (config.isGuiMode()) {
            cdd_sync_log.setMode(true);
        } else {
            cdd_sync_log.setMode(false);
        }

        try {
            cdd_sync_log.info("=== Serato Session Path Fixer ===");
            cdd_sync_log.info("");

            String seratoPath = config.getSeratoPath();
            String[] musicPaths = config.getMusicLibraryPaths();

            // Validate Serato path
            File seratoDir = new File(seratoPath);
            if (!seratoDir.exists()) {
                cdd_sync_log.error("Serato folder not found: " + seratoPath);
                cdd_sync_log.error("Please check your music.library.serato setting.");
                cdd_sync_log.fatalError();
            }

            File sessionsDir = new File(seratoPath + "/History/Sessions");
            if (!sessionsDir.exists()) {
                cdd_sync_log.error("History/Sessions folder not found in: " + seratoPath);
                cdd_sync_log.error("");
                cdd_sync_log.error("The History folder only exists in your HOME _Serato_ folder:");
                cdd_sync_log.error("  ~/Music/_serato_  (not on external drives)");
                cdd_sync_log.error("");
                cdd_sync_log.error("Please update music.library.serato in session-fixer.properties");
                cdd_sync_log.fatalError();
            }

            // Validate at least one music path exists
            int validPaths = 0;
            for (String path : musicPaths) {
                if (new File(path).exists()) {
                    validPaths++;
                } else {
                    cdd_sync_log.info("Note: Music path not found (will skip): " + path);
                }
            }
            if (validPaths == 0) {
                cdd_sync_log.error("No valid music library paths found.");
                cdd_sync_log.error("Please check your music.library.filesystem setting.");
                cdd_sync_log.fatalError();
            }

            cdd_sync_log.info("Serato folder: " + seratoPath);
            cdd_sync_log.info("Music libraries (" + musicPaths.length + " configured):");
            for (int i = 0; i < musicPaths.length; i++) {
                String status = new File(musicPaths[i]).exists() ? "OK" : "NOT FOUND";
                cdd_sync_log.info("  " + (i + 1) + ". " + musicPaths[i] + " [" + status + "]");
            }
            cdd_sync_log.info("");

            // Step 1: Backup (if enabled)
            if (config.isBackupEnabled()) {
                cdd_sync_log.info("Creating backup...");
                String backupPath = cdd_sync_backup.createBackup(seratoPath);
                if (backupPath == null) {
                    cdd_sync_log.error("Backup failed. Aborting to protect your data.");
                    cdd_sync_log.fatalError();
                }
                cdd_sync_log.info("");
            } else {
                cdd_sync_log.info("Warning: Backup is disabled. Proceeding without backup.");
                cdd_sync_log.info("");
            }

            // Step 2: Scan all music libraries
            cdd_sync_log.info("Scanning music libraries for file lookups...");
            java.util.List<cdd_sync_media_library> libraries = new java.util.ArrayList<>();
            for (String path : musicPaths) {
                if (new File(path).exists()) {
                    cdd_sync_log.info("  Scanning: " + path);
                    libraries.add(cdd_sync_media_library.readFrom(path));
                }
            }
            cdd_sync_log.info("");

            // Step 3: Clean up short sessions (before path fixing to save time)
            int minDuration = config.getMinSessionDuration();
            if (minDuration > 0) {
                session_fixer_core_logic.deleteShortSessions(seratoPath, minDuration);
            }

            // Step 4: Fix session paths (checking all libraries in order)
            session_fixer_core_logic.fixBrokenPaths(seratoPath, libraries, null);

            cdd_sync_log.info("");
            cdd_sync_log.info("=== Session Path Fixer Complete ===");

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
