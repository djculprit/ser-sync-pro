import java.io.*;
import java.util.*;

/**
 * Utility to manage Serato crate order in neworder.pref.
 * When sorting is enabled, deletes and recreates the file with alphabetically
 * sorted crates based on all .crate files in the Subcrates folder.
 */
public class cdd_sync_pref_sorter {

    private static final String PREF_FILE = "neworder.pref";
    private static final String SUBCRATES_DIR = "Subcrates";
    private static final String CRATE_EXTENSION = ".crate";
    private static final String BEGIN_MARKER = "[begin record]";
    private static final String END_MARKER = "[end record]";
    private static final String CRATE_MARKER = "[crate]";

    /**
     * Sorts and recreates neworder.pref by scanning all .crate files in Subcrates.
     * 
     * @param seratoPath Path to the Serato library folder (e.g., _Serato_)
     */
    public static void sort(String seratoPath) {
        File prefFile = new File(seratoPath, PREF_FILE);

        // Delete existing file if present
        if (prefFile.exists()) {
            if (!prefFile.delete()) {
                cdd_sync_log.error("Failed to delete existing '" + PREF_FILE + "'. Skipping crate sorting.");
                return;
            }
            cdd_sync_log.info("Deleted existing '" + PREF_FILE + "' for recreation.");
        }

        // Scan Subcrates folder for all .crate files
        List<String> crateNames = scanSubcratesFolder(seratoPath);

        if (crateNames.isEmpty()) {
            cdd_sync_log.info("No crates found in '" + SUBCRATES_DIR + "'. Skipping.");
            return;
        }

        cdd_sync_log.info("Creating '" + PREF_FILE + "' with " + crateNames.size() + " crates...");

        // Sort alphabetically
        Collections.sort(crateNames);

        try {
            // Write with UTF-16BE encoding to match Serato's expected format
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(prefFile), "UTF-16BE");
            writer.write(BEGIN_MARKER + "\n");
            for (String crate : crateNames) {
                writer.write(CRATE_MARKER + crate + "\n");
            }
            writer.write(END_MARKER + "\n");
            writer.close();

            cdd_sync_log.info("Successfully created '" + PREF_FILE + "' with " + crateNames.size()
                    + " crates sorted alphabetically.");

        } catch (IOException e) {
            cdd_sync_log.error("Failed to create '" + PREF_FILE + "': " + e.getMessage());
        }
    }

    /**
     * Scans the Subcrates folder and returns all crate names.
     * Crate names are derived from filenames by removing the .crate extension.
     * 
     * @param seratoPath Path to the Serato library folder
     * @return List of crate names found
     */
    private static List<String> scanSubcratesFolder(String seratoPath) {
        List<String> crateNames = new ArrayList<>();
        File subcratesDir = new File(seratoPath, SUBCRATES_DIR);

        if (!subcratesDir.exists() || !subcratesDir.isDirectory()) {
            cdd_sync_log.info("Subcrates directory not found: " + subcratesDir.getAbsolutePath());
            return crateNames;
        }

        File[] crateFiles = subcratesDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(CRATE_EXTENSION);
            }
        });

        if (crateFiles == null) {
            return crateNames;
        }

        for (File crateFile : crateFiles) {
            String filename = crateFile.getName();
            // Remove .crate extension to get the crate name
            String crateName = filename.substring(0, filename.length() - CRATE_EXTENSION.length());
            crateNames.add(crateName);
        }

        cdd_sync_log.info("Found " + crateNames.size() + " crate files in '" + SUBCRATES_DIR + "'.");
        return crateNames;
    }
}
