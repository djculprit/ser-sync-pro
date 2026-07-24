import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration loader for date-fixer.
 * Reads settings from date-fixer.properties file.
 */
public class date_fixer_config {

    public static final String CONFIG_FILE     = "date-fixer.properties";
    public static final String CONFIG_FILE_ALT = "date-fixer.properties.txt";

    private final Properties properties;

    public date_fixer_config() throws IOException {
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

    public boolean isDryRun() {
        return getBooleanOption("dry.run", false);
    }

    // ==================== Paths ====================

    /** Absolute path to the Serato _Serato_ folder (contains "database V2"). */
    public String getSeratoPath() {
        return expandPath(getRequiredOption("music.library.serato"));
    }

    /** Absolute path to the music library root (used for exFAT volume detection). */
    public String getMusicLibraryPath() {
        return expandPath(getRequiredOption("music.library.filesystem"));
    }

    // ==================== Optional Features ====================

    /** If true, also write st_birthtime (Date Created) via SetFile. Off by default. */
    public boolean isWriteDateCreatedEnabled() {
        return getBooleanOption("write.date.created", false);
    }

    // ==================== Helper Methods ====================

    private String expandPath(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private boolean getBooleanOption(String name, boolean defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) return defaultValue;
        return "true".equalsIgnoreCase(value.trim());
    }

    private String getRequiredOption(String name) {
        String result = properties.getProperty(name);
        if (result == null || result.trim().isEmpty()) {
            throw new cdd_sync_fatal_exception("Required config option missing: " + name);
        }
        return result.trim();
    }
}
