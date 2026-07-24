import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration loader for cdd-sync-pro.
 * Reads settings from cdd-sync.properties file.
 */
public class cdd_sync_config {

    public static final String CONFIG_FILE = "cdd-sync.properties";
    public static final String CONFIG_FILE_ALT = "cdd-sync.properties.txt";

    private Properties properties;
    private boolean dryRun = false;

    /** File-based constructor for CLI mode */
    public cdd_sync_config() throws IOException {
        properties = new Properties();
        try (FileInputStream in = openConfigFile()) {
            properties.load(in);
        }
    }

    private static FileInputStream openConfigFile() throws IOException {
        try {
            return new FileInputStream(CONFIG_FILE);
        } catch (FileNotFoundException e) {
            return new FileInputStream(CONFIG_FILE_ALT);
        }
    }

    /** Properties-based constructor for GUI mode */
    public cdd_sync_config(Properties props) {
        this.properties = props;
    }

    /** Returns the raw properties (for GUI pre-population) */
    public Properties getProperties() {
        return properties;
    }

    // ==================== Mode ====================

    public boolean isGuiMode() {
        return !("cmd".equals(properties.getProperty("mode")));
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    // ==================== Paths ====================

    public String getMusicLibraryPath() {
        return getRequiredOption("music.library.filesystem");
    }

    public String getSeratoLibraryPath() {
        return getRequiredOption("music.library.database");
    }

    // ==================== Parent Crate ====================

    public String getParentCratePath() {
        String path = properties.getProperty("crate.parent.path");
        if (path == null || path.trim().length() <= 0) {
            return null;
        }
        path = path.trim();
        // Reject nested paths
        if (path.contains("%%")) {
            cdd_sync_log.error("Invalid 'crate.parent.path': nested paths are not supported.");
            cdd_sync_log.error("Use a single crate name like 'Current', not 'Current%%2025'.");
            throw new cdd_sync_fatal_exception("Invalid crate.parent.path: nested paths not supported");
        }
        return path;
    }

    // ==================== Sync Options ====================

    public boolean isClearLibraryBeforeSync() {
        return getBooleanOption("music.library.database.clear-before-sync", false);
    }

    // ==================== Backup ====================

    public boolean isBackupEnabled() {
        return getBooleanOption("music.library.database.backup", true);
    }

    // ==================== Deduplication ====================

    public boolean isHardDriveDupeScanEnabled() {
        return getBooleanOption("harddrive.dupe.scan.enabled", false);
    }

    // Dupe move mode constants - named by what we KEEP
    public static final String DUPE_MOVE_KEEP_NEWEST = "keep-newest"; // moves older files
    public static final String DUPE_MOVE_KEEP_OLDEST = "keep-oldest"; // moves newer files
    public static final String DUPE_MOVE_OFF = "false";

    /**
     * Gets the duplicate move mode.
     * 
     * @return "keep-newest" (move older files), "keep-oldest" (move newer files),
     *         or "false" (disabled)
     */
    public String getDupeMoveMode() {
        String mode = properties.getProperty("harddrive.dupe.move.enabled");
        if (mode == null || mode.trim().isEmpty()) {
            return DUPE_MOVE_OFF;
        }
        mode = mode.trim().toLowerCase();
        // Backward compatibility aliases
        if ("true".equals(mode) || "oldest".equals(mode)) {
            return DUPE_MOVE_KEEP_NEWEST; // original behavior: keep newest, move oldest
        }
        if ("newest".equals(mode)) {
            return DUPE_MOVE_KEEP_OLDEST; // keep oldest, move newest
        }
        return mode;
    }

    public boolean isDupeMoveEnabled() {
        String mode = getDupeMoveMode();
        return DUPE_MOVE_KEEP_NEWEST.equals(mode) || DUPE_MOVE_KEEP_OLDEST.equals(mode);
    }

    public String getDupeDetectionMode() {
        String mode = properties.getProperty("harddrive.dupe.detection.mode");
        if (mode == null || mode.trim().isEmpty()) {
            return "off"; // default
        }
        return mode.trim().toLowerCase();
    }

    public boolean isCrateSortingEnabled() {
        return getBooleanOption("crate.sorting.alphabetical", false);
    }

    // ==================== Pipeline Step Toggles ====================

    /** Step 0: Duplicate management (move + log-only scan). Default true. */
    public boolean isStep0Enabled() {
        return getBooleanOption("sync.step0.enabled", true);
    }

    /** Step 1: Fix broken paths in database V2. Default true. */
    public boolean isStep1Enabled() {
        return getBooleanOption("sync.step1.enabled", true);
    }

    /** Step 2: Fix broken paths in existing crates. Default true. */
    public boolean isStep2Enabled() {
        return getBooleanOption("sync.step2.enabled", true);
    }

    /** Step 3: Append new tracks to existing crates. Default true. */
    public boolean isStep3Enabled() {
        return getBooleanOption("sync.step3.enabled", true);
    }

    /** Step 4: Create new crates for new library paths. Default true. */
    public boolean isStep4Enabled() {
        return getBooleanOption("sync.step4.enabled", true);
    }

    // ==================== Helper Methods ====================

    private boolean getBooleanOption(String name, boolean defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    private String getRequiredOption(String name) {
        String result = properties.getProperty(name);
        if (result == null || result.trim().length() <= 0) {
            throw new cdd_sync_fatal_exception("Required config option missing: " + name);
        }
        return result;
    }
}
