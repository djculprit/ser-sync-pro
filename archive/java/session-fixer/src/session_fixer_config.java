import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration loader for session-fixer.
 * Reads settings from session-fixer.properties file.
 */
public class session_fixer_config {

    public static final String CONFIG_FILE = "session-fixer.properties";
    public static final String CONFIG_FILE_ALT = "session-fixer.properties.txt";

    private Properties properties;

    public session_fixer_config() throws IOException {
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

    // ==================== Mode ====================

    public boolean isGuiMode() {
        return !("cmd".equals(properties.getProperty("mode")));
    }

    // ==================== Paths ====================

    /**
     * Gets the music library filesystem paths.
     * Supports multiple paths separated by commas.
     * Paths are checked in order - first match wins.
     */
    public String[] getMusicLibraryPaths() {
        String raw = getRequiredOption("music.library.filesystem");
        String[] paths = raw.split(",");
        String[] result = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            result[i] = expandPath(paths[i].trim());
        }
        return result;
    }

    /**
     * Gets the Serato folder path.
     * This MUST be the _Serato_ folder in ~/Music/ that contains History/Sessions.
     */
    public String getSeratoPath() {
        return expandPath(getRequiredOption("music.library.serato"));
    }

    // ==================== Backup ====================

    public boolean isBackupEnabled() {
        return getBooleanOption("backup.enabled", true);
    }

    // ==================== Session Cleanup ====================

    /**
     * Gets minimum session duration in minutes.
     * Sessions shorter than this will be deleted.
     * Returns 0 if not set or blank (feature disabled).
     */
    public int getMinSessionDuration() {
        String value = properties.getProperty("session.min.duration");
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Expands ~ to user home directory.
     */
    private String expandPath(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

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
        return result.trim();
    }
}
