import java.io.File;

/**
 * File and directory utilities for cdd-sync-pro.
 */
public class cdd_sync_file_utils {

    /**
     * Deletes all files in a directory (not subdirectories).
     */
    public static void deleteAllFilesInDirectory(String directoryPath) {
        File directory = new File(directoryPath);
        if (directory.isDirectory()) {
            File[] all = directory.listFiles();
            if (all != null) {
                for (File file : all) {
                    if (file.isFile() && !file.delete()) {
                        cdd_sync_log.error("Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    /**
     * Deletes a single file.
     */
    public static void deleteFile(String filePath) {
        File file = new File(filePath);
        if (file.exists() && !file.delete()) {
            cdd_sync_log.error("Failed to delete file: " + filePath);
        }
    }
}
