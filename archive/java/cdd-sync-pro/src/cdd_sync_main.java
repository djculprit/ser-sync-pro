import java.io.File;
import java.io.IOException;
import java.util.Properties;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/**
 * Main entry point for cdd-sync-pro.
 * Syncs filesystem directory structure to Serato crates.
 */
public class cdd_sync_main {

    public static void main(String[] args) {
        boolean dryRunFlag = false;
        for (String arg : args) {
            if ("--dry-run".equals(arg)) {
                dryRunFlag = true;
            }
        }

        // Try to load config to check mode
        cdd_sync_config initialConfig;
        try {
            initialConfig = new cdd_sync_config();
        } catch (IOException e) {
            // No config file — default to GUI mode
            initialConfig = null;
        }

        if (dryRunFlag && initialConfig != null) {
            initialConfig.setDryRun(true);
        }

        boolean guiMode = (initialConfig == null) || initialConfig.isGuiMode();

        if (guiMode) {
            launchGui(initialConfig);
        } else {
            runSync(initialConfig);
            System.exit(0);
        }
    }

    /**
     * GUI mode: Show the config window, wait for Start click, then sync.
     */
    private static void launchGui(cdd_sync_config initialConfig) {
        // Use cross-platform L&F so our dark theme colors render correctly
        // (macOS Aqua L&F ignores setBackground on most components)
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to default L&F
        }

        // macOS dark title bar
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");

        javax.swing.SwingUtilities.invokeLater(() -> {
            cdd_sync_pro_window window = new cdd_sync_pro_window();

            // Install the window as the log handler BEFORE any logging
            cdd_sync_log_window_handler handler = new cdd_sync_log_window_handler(window);
            cdd_sync_log_window_handler.install(handler);
            cdd_sync_log.setMode(true);

            // Load saved settings into controls
            if (initialConfig != null) {
                window.loadFromProperties(initialConfig.getProperties());
            }

            // When Start is clicked, run sync on background thread
            window.setOnStartCallback(() -> {
                SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() {
                        try {
                            Properties guiProps = window.collectProperties();
                            cdd_sync_config config = new cdd_sync_config(guiProps);
                            runSync(config);
                        } catch (cdd_sync_fatal_exception e) {
                            cdd_sync_log.error("Fatal: " + e.getMessage());
                        }
                        return null;
                    }

                    @Override
                    protected void done() {
                        window.syncFinished();
                    }
                };
                worker.execute();
            });
        });
    }

    /**
     * Core sync logic — used by both GUI and CLI modes.
     */
    private static void runSync(cdd_sync_config config) {
        // Set mode (GUI vs command line)
        cdd_sync_log.setMode(config.isGuiMode());

        // Check Serato library exists
        String seratoPath = config.getSeratoLibraryPath();

        // Set log directory to volume (alongside backup and dupes)
        File seratoDir = new File(seratoPath);
        File volumeLogDir = new File(seratoDir.getParentFile(), "cdd-sync-pro/logs");
        cdd_sync_log.setLogDirectory(volumeLogDir.getAbsolutePath());

        cdd_sync_log.info("cdd-sync-pro started");

        // Backup Serato folder
        if (config.isBackupEnabled()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: created backup of " + seratoPath);
            } else {
                String backupPath = cdd_sync_backup.createBackup(seratoPath);
                if (backupPath == null) {
                    cdd_sync_log.error("Backup failed. Aborting sync for safety.");
                    cdd_sync_log.fatalError();
                    return;
                }
            }
        }

        // Load media library
        cdd_sync_log.info("Scanning media library " + config.getMusicLibraryPath() + "...");
        cdd_sync_media_library fsLibrary = cdd_sync_media_library.readFrom(config.getMusicLibraryPath());
        if (fsLibrary.getTotalNumberOfTracks() <= 0) {
            cdd_sync_log.error("Unable to find any supported files in your media library directory.");
            cdd_sync_log.error("Are you sure you specified the right path in the config file?");
            cdd_sync_log.fatalError();
            return;
        }
        cdd_sync_log.info("Found " + fsLibrary.getTotalNumberOfTracks() + " tracks in " +
                fsLibrary.getTotalNumberOfDirectories() + " directories");

        // ── Step 0: Duplicate management (move + log-only scan) ──────────────────
        // Early move block runs before Step 1 so crates never point to moved files.
        // Toggle sync.step0.enabled=false to skip entirely for debugging.
        if (config.isStep0Enabled()) {
            if (config.isDupeMoveEnabled()) {
                if (config.isDryRun()) {
                    cdd_sync_log.info("[DRY RUN] Would have: scanned and moved duplicate files ("
                            + config.getDupeMoveMode() + ")");
                } else {
                    java.util.Map<String, String> movedToKept = cdd_sync_dupe_mover.scanAndMoveDuplicates(
                            config.getMusicLibraryPath(), fsLibrary, config.getDupeDetectionMode(),
                            config.getDupeMoveMode());
                    if (!movedToKept.isEmpty()) {
                        String databasePath = seratoPath + "/database V2";
                        int dbUpdated = cdd_sync_database_fixer.updatePaths(databasePath, movedToKept);
                        if (dbUpdated > 0) {
                            cdd_sync_log.info("Updated " + dbUpdated + " paths in database V2 for moved duplicates");
                        }
                        cdd_sync_log.info("Rescanning media library after duplicate removal...");
                        fsLibrary = cdd_sync_media_library.readFrom(config.getMusicLibraryPath());
                        cdd_sync_log.info("Found " + fsLibrary.getTotalNumberOfTracks() + " tracks remaining.");
                    }
                }
            }
        } else {
            cdd_sync_log.info("Step 0 skipped: duplicate management toggle is off.");
        }

        cdd_sync_log.info("Writing files into serato library " + seratoPath + "...");
        if (!new File(seratoPath).isDirectory()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: created Serato library folder " + seratoPath);
            } else {
                boolean createFolder = cdd_sync_log.confirm(
                        "Serato library folder '" + seratoPath + "' does not exist.\n\n" +
                                "Would you like to create it and continue with the sync?");
                if (createFolder) {
                    boolean created = new File(seratoPath).mkdirs();
                    if (created) {
                        cdd_sync_log.info("Created Serato library folder: " + seratoPath);
                    } else {
                        cdd_sync_log.error("Failed to create Serato library folder: " + seratoPath);
                        cdd_sync_log.fatalError();
                        return;
                    }
                } else {
                    cdd_sync_log.info("Sync halted by user.");
                    cdd_sync_log.fatalError();
                    return;
                }
            }
        }

        // Load Serato database for path normalization
        cdd_sync_database database = cdd_sync_database.readFrom(seratoPath + "/database V2");
        if (database == null) {
            cdd_sync_log.info("No existing Serato database found. Skipping path normalization.");
        }

        // Validate parent crate path
        String parentCratePath = config.getParentCratePath();
        if (parentCratePath != null) {
            cdd_sync_log.info("Using parent crate: " + parentCratePath);

            File parentCrateFile = new File(seratoPath + "/Subcrates/" + parentCratePath + ".crate");
            if (!parentCrateFile.exists()) {
                cdd_sync_log
                        .info("Parent crate '" + parentCratePath + "' does not exist. Creating it automatically...");
                if (config.isDryRun()) {
                    cdd_sync_log.info("[DRY RUN] Would have: created parent crate " + parentCratePath);
                } else {
                    try {
                        parentCrateFile.getParentFile().mkdirs();
                        cdd_sync_crate emptyCrate = new cdd_sync_crate();
                        emptyCrate.writeTo(parentCrateFile);
                    } catch (cdd_sync_exception e) {
                        cdd_sync_log.error("Failed to create parent crate '" + parentCratePath + "'");
                        cdd_sync_log.error(e);
                        cdd_sync_log.fatalError();
                        return;
                    }
                }
            }

            // Check for duplicates
            File subcratesDir = new File(seratoPath + "/Subcrates");
            if (subcratesDir.isDirectory()) {
                int count = 0;
                File[] crateFiles = subcratesDir.listFiles();
                if (crateFiles != null) {
                    for (File f : crateFiles) {
                        if (f.getName().equalsIgnoreCase(parentCratePath + ".crate")) {
                            count++;
                        }
                    }
                }
                if (count > 1) {
                    cdd_sync_log.error("Duplicate parent crate detected: found " + count + " crates named '"
                            + parentCratePath + "'.");
                    cdd_sync_log.error("Please resolve the duplication in Serato before syncing.");
                    cdd_sync_log.fatalError();
                    return;
                }
            }
        }

        // Load track index — provides database V2 reference for path encoding in crates
        cdd_sync_track_index trackIndex = cdd_sync_track_index.createFrom(seratoPath);

        // Clear library if requested (nuke before full rebuild via steps 3 & 4)
        if (config.isClearLibraryBeforeSync()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: cleared existing Serato library");
            } else {
                cdd_sync_file_utils.deleteAllFilesInDirectory(seratoPath + "/Crates");
                cdd_sync_file_utils.deleteAllFilesInDirectory(seratoPath + "/Subcrates");
                cdd_sync_file_utils.deleteFile(seratoPath + "/database V2");
            }
        }

        // ── Step 1: Fix broken paths in database V2 ──────────────────────────────
        // Updates stale pfil paths in the database. No crate files touched.
        // Skipped when Clear Library is on (database was just deleted).
        if (!config.isClearLibraryBeforeSync() && config.isStep1Enabled()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: updated broken paths in database V2");
            } else {
                cdd_sync_crate_fixer.updateDatabasePaths(seratoPath, fsLibrary);
            }
        } else if (!config.isStep1Enabled()) {
            cdd_sync_log.info("Step 1 skipped: step1 toggle is off.");
        } else {
            cdd_sync_log.info("Step 1 skipped: Clear Library is on (database was deleted).");
        }

        // ── Step 2: Fix broken paths in existing crates via filesystem ────────────
        // For every track in every crate, looks up the filename in the scanned
        // media library. If the library holds a different (unambiguous) path,
        // the crate's stored path is replaced. Covers ALL crates — hand-curated
        // Live sets included. Fully decoupled from the database so tracks that
        // Serato has never indexed are also fixed.
        // Skipped when Clear Library is on (crates were just deleted).
        if (!config.isClearLibraryBeforeSync() && config.isStep2Enabled()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: updated crate paths from filesystem");
            } else {
                cdd_sync_crate_fixer.fixExistingCrates(seratoPath, fsLibrary, database);
            }
        } else if (!config.isStep2Enabled()) {
            cdd_sync_log.info("Step 2 skipped: step2 toggle is off.");
        } else {
            cdd_sync_log.info("Step 2 skipped: Clear Library is on (crates were deleted).");
        }

        // ── Step 3: Append new tracks to existing crates matching filepath ─────────
        // For each folder-mapped crate already on disk, adds any new tracks from
        // that folder. addTrack() dedup prevents duplicates. No removal.
        if (config.isStep3Enabled()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: appended new tracks to existing crates");
            } else {
                cdd_sync_crate_fixer.appendNewTracksToMatchingCrates(
                        seratoPath, fsLibrary, parentCratePath, trackIndex);
            }
        } else {
            cdd_sync_log.info("Step 3 skipped: step3 toggle is off.");
        }

        // ── Step 4: Create new crates for new library paths (skip existing) ────────
        // Only writes a .crate if no file with that name exists on disk.
        // Existing crates (hand-curated or folder-mapped) are never touched.
        if (config.isStep4Enabled()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: created new crates for new library paths");
            } else {
                cdd_sync_crate_fixer.createNewCrates(
                        seratoPath, fsLibrary, parentCratePath, trackIndex);
            }
        } else {
            cdd_sync_log.info("Step 4 skipped: step4 toggle is off.");
        }

        // ── Step 0 (late): Log-only duplicate scan ───────────────────────────────
        // Runs after crates are built. Only active when move mode is off.
        if (config.isHardDriveDupeScanEnabled() && !config.isDupeMoveEnabled()
                && config.isStep0Enabled()) {
            scanForHardDriveDuplicates(fsLibrary);
        }

        cdd_sync_log.info("Sync Complete");

        // Sort crates if enabled
        if (config.isCrateSortingEnabled()) {
            if (config.isDryRun()) {
                cdd_sync_log.info("[DRY RUN] Would have: sorted crates alphabetically in neworder.pref");
            } else {
                cdd_sync_pref_sorter.sort(seratoPath);
            }
        }

        if (config.isDryRun()) {
            cdd_sync_log.info("[DRY RUN] Sync complete — no files were written.");
        } else {
            cdd_sync_log.success();
        }
    }


    private static void scanForHardDriveDuplicates(cdd_sync_media_library library) {
        cdd_sync_log.info("Scanning for hard drive duplicates...");
        java.util.List<String> allTracks = new java.util.ArrayList<>();
        library.flattenTracks(allTracks);

        java.util.Map<String, java.util.List<String>> groups = new java.util.HashMap<>();
        for (String path : allTracks) {
            java.io.File f = new java.io.File(path);
            String key = f.getName().toLowerCase() + "|" + f.length();
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(path);
        }

        int dupeCount = 0;
        for (java.util.Map.Entry<String, java.util.List<String>> entry : groups.entrySet()) {
            java.util.List<String> paths = entry.getValue();
            if (paths.size() > 1) {
                dupeCount++;
                cdd_sync_log.dupe("Duplicate group: " + entry.getKey());
                for (String path : paths) {
                    cdd_sync_log.dupe("  " + path);
                }
                cdd_sync_log.dupe("");
            }
        }

        if (dupeCount > 0) {
            cdd_sync_log.info("Found " + dupeCount
                    + " duplicate file groups on hard drive. See logs/cdd-sync-dupe-files-*.log for details.");
        } else {
            cdd_sync_log.info("No hard drive duplicates found.");
        }
    }
}
