import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;

/**
 * Logging utility for cdd-sync-pro.
 * Supports GUI and command-line modes, with optional file logging.
 */
public class cdd_sync_log {

    private static boolean GUI_INITIALIZED = false;
    private static boolean GUI_MODE = true;
    private static cdd_sync_log_window_handler WINDOW_HANDLER;
    private static PrintWriter FILE_WRITER;
    private static PrintWriter DUPE_WRITER;
    private static PrintWriter FIX_WRITER;
    private static PrintWriter STEP1_WRITER;
    private static PrintWriter STEP2_WRITER;
    private static PrintWriter STEP3_WRITER;
    private static PrintWriter STEP4_WRITER;
    private static final String DUPE_FILE = "cdd-sync-dupe-files.log";
    private static String LOG_DIR = null; // null = use CWD-relative "logs/"

    /**
     * Sets the log directory path. If set, logs are written to this directory
     * instead of CWD-relative "logs/". Must be called before any logging methods.
     */
    public static synchronized void setLogDirectory(String directory) {
        LOG_DIR = directory;
        // Close any existing log writers before the next run opens fresh ones.
        // Resetting GUI_INITIALIZED causes initLogFile() to be called again on
        // the next log write — necessary for repeated GUI sync runs in the same
        // JVM session (e.g. clicking Start twice in the config window).
        closeLogFile();
        GUI_INITIALIZED = false;
    }

    public static void info(String message) {
        initGui();
        String timestamped = getTimestamp() + " [INFO] " + message;

        if (GUI_MODE) {
            WINDOW_HANDLER.publish(message);
        } else {
            System.out.println(message);
            System.out.flush();
        }
        writeToFile(timestamped);
    }

    // Progress tracking state
    private static volatile long progressStartTime = 0;
    private static volatile String lastProgressTask = "";
    private static volatile int lastProgressPercent = -1;

    /**
     * Displays progress with percentage and estimated time remaining.
     * Only updates display when percentage changes to avoid console spam.
     * 
     * @param task    Name of the task (e.g., "Scanning library", "Writing crates")
     * @param current Current item number (1-based)
     * @param total   Total number of items
     */
    public static synchronized void progress(String task, int current, int total) {
        if (total <= 0)
            return;

        initGui();

        // Reset start time if task changed
        if (!task.equals(lastProgressTask)) {
            progressStartTime = System.currentTimeMillis();
            lastProgressTask = task;
            lastProgressPercent = -1;
        }

        // Use long to avoid integer overflow for large files (current * 100 can exceed
        // int max)
        int percent = (int) ((long) current * 100 / total);

        // Only update if percentage changed (reduces output spam)
        if (percent == lastProgressPercent && current < total) {
            return;
        }
        lastProgressPercent = percent;

        // Calculate ETA
        String eta = "";
        if (current > 0 && progressStartTime > 0) {
            long elapsed = System.currentTimeMillis() - progressStartTime;
            if (elapsed > 500 && percent > 0 && percent < 100) { // Only show ETA after 500ms
                long estimatedTotal = (elapsed * 100) / percent;
                long remaining = estimatedTotal - elapsed;
                if (remaining > 1000) {
                    long seconds = remaining / 1000;
                    if (seconds > 60) {
                        eta = " (ETA: " + (seconds / 60) + "m " + (seconds % 60) + "s)";
                    } else {
                        eta = " (ETA: " + seconds + "s)";
                    }
                }
            }
        }

        String message = task + ": " + percent + "% (" + current + "/" + total + ")" + eta;

        if (GUI_MODE) {
            WINDOW_HANDLER.setProgress(message, percent);
        } else {
            // Use carriage return to update in place for CLI
            System.out.print("\r" + message + "          ");
            if (current >= total) {
                System.out.println(); // New line when complete
            }
            System.out.flush();
        }
    }

    /**
     * Clears progress display after task completion.
     */
    public static void progressComplete(String task) {
        lastProgressPercent = -1;
        lastProgressTask = "";
        progressStartTime = 0;

        if (GUI_MODE) {
            WINDOW_HANDLER.setProgress("", 0);
        }
    }

    public static void dupe(String message) {
        initGui();
        if (DUPE_WRITER != null) {
            DUPE_WRITER.println(message);
            DUPE_WRITER.flush();
        }
    }

    /**
     * Logs a detailed path-fix or crate-change record to the fix log file only.
     * Does NOT publish to the GUI — safe to call from parallel threads at high volume.
     * Use this for per-path and per-crate detail that would flood the GUI if shown.
     */
    public static synchronized void fix(String message) {
        initGui();
        if (FIX_WRITER != null) {
            FIX_WRITER.println(getTimestamp() + " " + message);
            FIX_WRITER.flush();
        }
    }

    /** Step 1 — database V2 path fixes. */
    public static synchronized void step1(String message) {
        initGui();
        if (STEP1_WRITER != null) {
            STEP1_WRITER.println(getTimestamp() + " " + message);
            STEP1_WRITER.flush();
        }
    }

    /** Step 2 — existing crate path fixes. */
    public static synchronized void step2(String message) {
        initGui();
        if (STEP2_WRITER != null) {
            STEP2_WRITER.println(getTimestamp() + " " + message);
            STEP2_WRITER.flush();
        }
    }

    /** Step 3 — append new tracks to existing crates. */
    public static synchronized void step3(String message) {
        initGui();
        if (STEP3_WRITER != null) {
            STEP3_WRITER.println(getTimestamp() + " " + message);
            STEP3_WRITER.flush();
        }
    }

    /** Step 4 — create new crates. */
    public static synchronized void step4(String message) {
        initGui();
        if (STEP4_WRITER != null) {
            STEP4_WRITER.println(getTimestamp() + " " + message);
            STEP4_WRITER.flush();
        }
    }

    public static void error(String message) {
        initGui();
        String timestamped = getTimestamp() + " [ERROR] " + message;

        if (GUI_MODE) {
            WINDOW_HANDLER.publish(message);
        } else {
            System.err.println(message);
            System.err.flush();
        }
        writeToFile(timestamped);
    }

    public static void error(Exception e) {
        initGui();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(out));
        String stackTrace = out.toString();

        if (GUI_MODE) {
            WINDOW_HANDLER.publish(stackTrace);
        } else {
            e.printStackTrace(System.err);
            System.err.flush();
        }
        writeToFile(getTimestamp() + " [ERROR] " + stackTrace);
    }

    public static void fatalError() {
        initGui();
        if (GUI_MODE) {
            WINDOW_HANDLER.fatalError();
        }
        closeLogFile();
        throw new cdd_sync_fatal_exception("Fatal error");
    }

    public static void success() {
        initGui();
        if (GUI_MODE) {
            WINDOW_HANDLER.success();
            // Don't exit - keep window open for log review
        } else {
            closeLogFile();
        }
    }

    public static boolean confirm(String message) {
        initGui();
        if (GUI_MODE) {
            return WINDOW_HANDLER.confirm(message);
        } else {
            System.out.println(message + " [y/n]: ");
            System.out.flush();
            try (Scanner scanner = new Scanner(System.in)) {
                String input = scanner.nextLine().trim().toLowerCase();
                return input.equals("y") || input.equals("yes");
            }
        }
    }

    private static synchronized void initGui() {
        if (!GUI_INITIALIZED) {
            if (GUI_MODE) {
                try {
                    WINDOW_HANDLER = cdd_sync_log_window_handler.getInstance();
                } catch (Exception e) {
                    GUI_MODE = false;
                }
            }
            initLogFile();
            GUI_INITIALIZED = true;
        }
    }

    private static void initLogFile() {
        try {
            // Use configured log directory or fall back to CWD-relative "logs/"
            File logsDir = LOG_DIR != null ? new File(LOG_DIR) : new File("logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            // Create timestamped log filename
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            String logPath = new File(logsDir, "cdd-sync-pro-" + timestamp + ".log").getAbsolutePath();

            FILE_WRITER = new PrintWriter(new FileWriter(logPath, false));
            FILE_WRITER.println(getTimestamp() + " [INFO] cdd-sync-pro started");
            FILE_WRITER.flush();
            String dupeLogPath = new File(logsDir, "cdd-sync-dupe-files-" + timestamp + ".log").getAbsolutePath();
            DUPE_WRITER = new PrintWriter(new FileWriter(dupeLogPath, false));
            String fixLogPath = new File(logsDir, "cdd-sync-path-fixes-" + timestamp + ".log").getAbsolutePath();
            FIX_WRITER = new PrintWriter(new FileWriter(fixLogPath, false));
            FIX_WRITER.println(getTimestamp() + " [FIX-LOG] cdd-sync-pro path fix log started");
            FIX_WRITER.flush();

            STEP1_WRITER = new PrintWriter(new FileWriter(
                    new File(logsDir, "cdd-sync-step1-db-fix-" + timestamp + ".log").getAbsolutePath(), false));
            STEP1_WRITER.println(getTimestamp() + " [STEP1] Database V2 path fix log started");
            STEP1_WRITER.flush();

            STEP2_WRITER = new PrintWriter(new FileWriter(
                    new File(logsDir, "cdd-sync-step2-crate-fix-" + timestamp + ".log").getAbsolutePath(), false));
            STEP2_WRITER.println(getTimestamp() + " [STEP2] Existing crate path fix log started");
            STEP2_WRITER.flush();

            STEP3_WRITER = new PrintWriter(new FileWriter(
                    new File(logsDir, "cdd-sync-step3-append-" + timestamp + ".log").getAbsolutePath(), false));
            STEP3_WRITER.println(getTimestamp() + " [STEP3] Crate append log started");
            STEP3_WRITER.flush();

            STEP4_WRITER = new PrintWriter(new FileWriter(
                    new File(logsDir, "cdd-sync-step4-create-" + timestamp + ".log").getAbsolutePath(), false));
            STEP4_WRITER.println(getTimestamp() + " [STEP4] New crate creation log started");
            STEP4_WRITER.flush();
        } catch (IOException e) {
            // Can't write log file - continue without it
        }
    }

    private static synchronized void writeToFile(String message) {
        if (FILE_WRITER != null) {
            FILE_WRITER.println(message);
            FILE_WRITER.flush();
        }
    }

    private static void closeLogFile() {
        if (FILE_WRITER != null) {
            FILE_WRITER.println(getTimestamp() + " [INFO] cdd-sync-pro finished");
            FILE_WRITER.close();
        }
        if (DUPE_WRITER != null) {
            DUPE_WRITER.close();
        }
        if (FIX_WRITER != null) {
            FIX_WRITER.close();
        }
        if (STEP1_WRITER != null) {
            STEP1_WRITER.close();
        }
        if (STEP2_WRITER != null) {
            STEP2_WRITER.close();
        }
        if (STEP3_WRITER != null) {
            STEP3_WRITER.close();
        }
        if (STEP4_WRITER != null) {
            STEP4_WRITER.close();
        }
    }

    private static String getTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    public static synchronized void setMode(boolean guiMode) {
        GUI_MODE = guiMode;
    }
}
