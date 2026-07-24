import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Core logic for date-fixer.
 *
 * Reads each track's uadd Unix timestamp from Serato database V2 and writes
 * com.apple.metadata:kMDItemDateAdded xattr to the file on disk.
 *
 * Platform: macOS only. Requires APFS or HFS+ for xattr persistence.
 * ExFAT volumes are detected and skipped with a warning.
 */
public class date_fixer_core_logic {

    // xattr key written to each file
    private static final String XATTR_DATE_ADDED = "com.apple.metadata:kMDItemDateAdded";

    // Volume root prefix used to convert Serato relative paths to absolute paths
    private String volumeRoot;
    private boolean dryRun;
    private boolean writeDateCreated;

    private int total   = 0;
    private int updated = 0;
    private int skipped = 0;
    private int errors  = 0;

    // Track volume format warnings so each volume is warned once
    private final Set<String> warnedExFATVolumes = new HashSet<>();

    /**
     * Entry point called by date_fixer_main.
     *
     * @param seratoPath        Absolute path to the _Serato_ folder
     * @param musicLibraryPath  Absolute path to the music library root
     *                          (used to determine volume root)
     * @param dryRun            If true, no xattr writes are performed
     * @param writeDateCreated  If true, also write st_birthtime via SetFile -d
     */
    public static void run(String seratoPath, String musicLibraryPath,
                           boolean dryRun, boolean writeDateCreated) {
        new date_fixer_core_logic(musicLibraryPath, dryRun, writeDateCreated)
                .process(seratoPath);
    }

    private date_fixer_core_logic(String musicLibraryPath, boolean dryRun, boolean writeDateCreated) {
        this.volumeRoot       = resolveVolumeRoot(musicLibraryPath);
        this.dryRun           = dryRun;
        this.writeDateCreated = writeDateCreated;
    }

    /**
     * Extracts the volume root from a music library path.
     * e.g. "/Volumes/Current/Crates" -> "/Volumes/Current"
     * e.g. "/Users/foo/Music"        -> "" (home volume, no prefix needed)
     */
    private static String resolveVolumeRoot(String musicLibraryPath) {
        if (musicLibraryPath.startsWith("/Volumes/")) {
            // /Volumes/<name>/...
            int third = musicLibraryPath.indexOf('/', "/Volumes/".length());
            if (third > 0) {
                return musicLibraryPath.substring(0, third);
            }
            return musicLibraryPath;
        }
        return ""; // Home volume — Serato pfil paths are already absolute
    }

    private void process(String seratoPath) {
        String dbPath = seratoPath + "/database V2";
        File dbFile = new File(dbPath);
        if (!dbFile.exists()) {
            cdd_sync_log.error("database V2 not found at: " + dbPath);
            throw new cdd_sync_fatal_exception("database V2 not found");
        }

        cdd_sync_log.info("Parsing Serato database: " + dbPath);
        cdd_sync_log.info("Volume root: " + (volumeRoot.isEmpty() ? "(home volume)" : volumeRoot));
        cdd_sync_log.info("");

        List<TrackRecord> records;
        try {
            records = parseDatabase(dbFile);
        } catch (IOException e) {
            cdd_sync_log.error("Failed to parse database V2: " + e.getMessage());
            throw new cdd_sync_fatal_exception("Database parse failed");
        }

        total = records.size();
        cdd_sync_log.info("Found " + total + " track records.");
        cdd_sync_log.info("");

        for (TrackRecord record : records) {
            processRecord(record);
        }

        cdd_sync_log.info("");
        cdd_sync_log.info("--- Summary ---");
        cdd_sync_log.info("Total records : " + total);
        cdd_sync_log.info("Updated       : " + updated);
        cdd_sync_log.info("Skipped       : " + skipped);
        cdd_sync_log.info("Errors        : " + errors);
        if (dryRun) {
            cdd_sync_log.info("[Dry run — no files were modified]");
        }
    }

    private void processRecord(TrackRecord record) {
        // Skip missing tracks
        if (record.missing) {
            skipped++;
            return;
        }

        // Resolve absolute path
        String absolutePath = resolveAbsolutePath(record.path);
        File targetFile = new File(absolutePath);

        if (!targetFile.exists()) {
            cdd_sync_log.info("[SKIP] File not found: " + absolutePath);
            skipped++;
            return;
        }

        // Check volume format (exFAT cannot store xattrs)
        String volume = getVolume(absolutePath);
        if (isExFAT(volume)) {
            if (!warnedExFATVolumes.contains(volume)) {
                cdd_sync_log.info("[WARN] exFAT volume detected — xattr writes not supported: " + volume);
                cdd_sync_log.info("       Skipping all tracks on this volume.");
                warnedExFATVolumes.add(volume);
            }
            skipped++;
            return;
        }

        if (record.uadd <= 0) {
            cdd_sync_log.info("[SKIP] No uadd timestamp: " + absolutePath);
            skipped++;
            return;
        }

        // Format date for log
        String dateStr = formatUnixDate(record.uadd);

        if (dryRun) {
            cdd_sync_log.info("[DRY RUN] Would set kMDItemDateAdded=" + dateStr + " on: " + absolutePath);
            updated++;
            return;
        }

        // Write kMDItemDateAdded xattr (binary plist NSDate)
        boolean xattrOk = writeKMDItemDateAdded(absolutePath, record.uadd);
        if (!xattrOk) {
            errors++;
            return;
        }

        // Optionally write Date Created (st_birthtime) via SetFile
        if (writeDateCreated) {
            writeDateCreated(absolutePath, record.uadd);
        }

        cdd_sync_log.info("[OK] " + dateStr + " -> " + absolutePath);
        updated++;
    }

    /**
     * Resolves a Serato-relative path (e.g. "Crates/Folder/File.mp3") to absolute.
     * If the path already starts with "/" it is used directly (home-volume tracks).
     */
    private String resolveAbsolutePath(String seratoPath) {
        // Serato stores paths as NFD; normalize to NFC for filesystem access on macOS
        String normalized = java.text.Normalizer.normalize(seratoPath, java.text.Normalizer.Form.NFC);
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return volumeRoot + "/" + normalized;
    }

    /**
     * Returns the volume root for a given absolute path.
     * e.g. "/Volumes/Current/foo/bar.mp3" -> "/Volumes/Current"
     * e.g. "/Users/foo/Music/bar.mp3"     -> "/"
     */
    private static String getVolume(String absolutePath) {
        if (absolutePath.startsWith("/Volumes/")) {
            int third = absolutePath.indexOf('/', "/Volumes/".length());
            if (third > 0) return absolutePath.substring(0, third);
        }
        return "/";
    }

    /**
     * Returns true if the given volume mount point is exFAT.
     * Uses `diskutil info` to query the filesystem type.
     */
    private static boolean isExFAT(String volumePath) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"diskutil", "info", volumePath});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("File System Personality") || line.contains("Type (Bundle)")) {
                    boolean exfat = line.toLowerCase().contains("exfat");
                    p.destroy();
                    return exfat;
                }
            }
            p.destroy();
        } catch (IOException e) {
            cdd_sync_log.info("[WARN] Could not determine volume format for: " + volumePath);
        }
        return false;
    }

    /**
     * Writes the com.apple.metadata:kMDItemDateAdded extended attribute.
     *
     * The value must be a binary property list encoding an NSDate (Apple epoch).
     * Apple epoch = Unix epoch - 978307200 (seconds between 1970-01-01 and 2001-01-01).
     *
     * Binary plist format for a single NSDate:
     *   62 70 6C 69 73 74 30 30  ("bplist00" magic)
     *   33                        (0x33 = NSDate object marker)
     *   <8 bytes big-endian IEEE 754 double> (Apple epoch seconds)
     *   08 09 00 00 00 00 00 00  (offset table: 1 object at offset 8 + 1 byte)
     *   00 00 00 00 00 00 00 01  (trailer: num objects = 1)
     *   00 00 00 00 00 00 00 00  (trailer: top object index = 0)
     *   00 00 00 00 00 00 00 09  (trailer: offset table offset = 9)
     *
     * We use `xattr -w` to write the hex-encoded binary plist value.
     * The xattr command accepts a binary plist via hex with -x flag.
     */
    private boolean writeKMDItemDateAdded(String filePath, long unixTimestamp) {
        // Convert Unix timestamp to Apple Core Data timestamp (seconds since 2001-01-01)
        double appleTimestamp = (double)(unixTimestamp - 978307200L);

        // Build binary plist manually (28 bytes total)
        byte[] bplist = buildNSDateBinaryPlist(appleTimestamp);

        // Convert to hex string for xattr -x
        StringBuilder hex = new StringBuilder();
        for (byte b : bplist) {
            hex.append(String.format("%02x", b & 0xFF));
        }

        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "xattr", "-wx", XATTR_DATE_ADDED, hex.toString(), filePath
            });
            int exit = p.waitFor();
            if (exit != 0) {
                BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                String errLine = err.readLine();
                cdd_sync_log.error("[ERROR] xattr write failed (exit " + exit + "): " + errLine);
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            cdd_sync_log.error("[ERROR] xattr exec failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Build a minimal binary plist containing a single NSDate value.
     * Total: 24 bytes.
     * Format: bplist00 | 0x33 | 8-byte double | offset table | trailer
     */
    private static byte[] buildNSDateBinaryPlist(double appleTimestamp) {
        byte[] plist = new byte[24];

        // Magic: "bplist00"
        byte[] magic = {0x62, 0x70, 0x6C, 0x69, 0x73, 0x74, 0x30, 0x30};
        System.arraycopy(magic, 0, plist, 0, 8);

        // NSDate marker: 0x33
        plist[8] = 0x33;

        // IEEE 754 double, big-endian (Apple epoch seconds)
        long bits = Double.doubleToRawLongBits(appleTimestamp);
        for (int i = 0; i < 8; i++) {
            plist[9 + i] = (byte)((bits >>> (56 - 8 * i)) & 0xFF);
        }

        // Offset table: object 0 is at byte offset 8 (0x08), 1 byte per entry
        plist[17] = 0x08;

        // Trailer (6 bytes padding + 1 byte offset size + 1 byte ref size
        //          + 8 bytes num objects + 8 bytes top obj + 8 bytes offset table offset)
        // Simplified 6-byte trailer at plist[18..23]:
        // num_obj=1, top_obj=0, offset_table_offset=9
        plist[18] = 0x01; // num objects = 1
        plist[19] = 0x00; // top object index = 0
        plist[20] = 0x00; // offset table offset high bytes (0)
        plist[21] = 0x00;
        plist[22] = 0x00;
        plist[23] = 0x11; // offset = 17 (0x11)

        return plist;
    }

    /**
     * Writes st_birthtime (Date Created) using SetFile -d.
     * SetFile is part of Xcode Command Line Tools.
     * Format required: "MM/DD/YYYY HH:MM:SS"
     * Falls back gracefully if SetFile is not available.
     */
    private void writeDateCreated(String filePath, long unixTimestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        String dateStr = sdf.format(new Date(unixTimestamp * 1000L));

        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "SetFile", "-d", dateStr, filePath
            });
            int exit = p.waitFor();
            if (exit != 0) {
                BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                String errLine = err.readLine();
                cdd_sync_log.info("[WARN] SetFile failed for: " + filePath + " (" + errLine + ")");
            }
        } catch (IOException | InterruptedException e) {
            cdd_sync_log.info("[WARN] SetFile not available: " + e.getMessage());
        }
    }

    private static String formatUnixDate(long unixTimestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(unixTimestamp * 1000L));
    }

    // ==================== Database Parser ====================

    /**
     * Parses Serato database V2 and returns a list of track records.
     * Extracts: pfil (path), uadd (unix timestamp), bmis (missing flag).
     */
    private List<TrackRecord> parseDatabase(File dbFile) throws IOException {
        List<TrackRecord> records = new ArrayList<>();
        byte[] data = java.nio.file.Files.readAllBytes(dbFile.toPath());
        int pos = 0;

        // Skip "vrsn" header
        if (pos + 8 > data.length || !"vrsn".equals(new String(data, pos, 4))) {
            throw new IOException("Invalid database V2: missing vrsn header");
        }
        pos += 4;
        int headerFlags = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        int headerLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        pos += headerLen;  // skip version string

        // Read otrk records
        while (pos + 8 <= data.length) {
            String tag = new String(data, pos, 4);
            if (!"otrk".equals(tag)) {
                pos++;  // resync
                continue;
            }
            pos += 4;
            int len = cdd_sync_binary_utils.readInt(data, pos);
            pos += 4;
            if (pos + len > data.length) break;

            TrackRecord record = parseTrackRecord(data, pos, len);
            if (record != null) {
                records.add(record);
            }
            pos += len;
        }
        return records;
    }

    private TrackRecord parseTrackRecord(byte[] data, int start, int len) {
        String path  = null;
        long   uadd  = 0;
        boolean missing = false;

        int pos = start;
        int end = start + len;

        while (pos + 8 <= end) {
            String tag = new String(data, pos, 4);
            pos += 4;
            int fieldLen = cdd_sync_binary_utils.readInt(data, pos);
            pos += 4;
            if (pos + fieldLen > end) break;

            switch (tag) {
                case "pfil":
                    try {
                        path = new String(data, pos, fieldLen, "UTF-16BE");
                    } catch (UnsupportedEncodingException e) {
                        path = new String(data, pos, fieldLen);
                    }
                    break;
                case "uadd":
                    uadd = cdd_sync_binary_utils.readInt(data, pos) & 0xFFFFFFFFL;
                    break;
                case "bmis":
                    missing = data[pos] != 0;
                    break;
            }
            pos += fieldLen;
        }

        if (path == null) return null;
        return new TrackRecord(path, uadd, missing);
    }

    // ==================== Inner Classes ====================

    private static class TrackRecord {
        final String  path;
        final long    uadd;
        final boolean missing;

        TrackRecord(String path, long uadd, boolean missing) {
            this.path    = path;
            this.uadd    = uadd;
            this.missing = missing;
        }
    }
}
