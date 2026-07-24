import java.io.*;
import java.text.Normalizer;
import java.util.*;

/**
 * Represents a single Serato crate.
 * Handles reading and writing .crate binary files.
 */
public class cdd_sync_crate {

    private static final String DEFAULT_VERSION = "81.0";
    private static final String DEFAULT_SORTING = "song";
    private static final long DEFAULT_SORTING_REV = 1 << 8;
    private static final String[] DEFAULT_COLUMNS = { "song", "artist", "album", "length" };

    private String version;
    private String sorting;
    private long sortingRev = Integer.MIN_VALUE;
    private List<String> columns = new ArrayList<>();
    private List<String> tracks = new ArrayList<>();
    private cdd_sync_database database; // Reference to database for path lookup
    // NFC-normalized path set for O(1) dedup in addTrack().
    // Keyed by normalizePath() so NFD filesystem paths and NFC crate paths
    // resolve to the same key (fixes duplicate insertion for filenames with
    // accented characters like "Bota Ni\u00f1a" where macOS returns NFD).
    private Set<String> normalizedTrackSet = new HashSet<>();
    // Raw payloads captured during readFrom() — written back verbatim to preserve
    // tvcw column widths and brev encoding set by Serato. Null/empty for new crates.
    private byte[] rawOsrtPayload = null;
    private List<byte[]> rawOvctPayloads = new ArrayList<>();

    public cdd_sync_crate() {
    }

    /**
     * Sets the database reference for path lookup.
     * When adding tracks, if the track exists in the database,
     * we use the exact database path to match encoding.
     */
    public void setDatabase(cdd_sync_database db) {
        this.database = db;
    }

    /**
     * Extracts the filename leaf and normalizes to NFC+lowercase for dedup.
     * Filename-only comparison is intentional: it is immune to relative vs absolute
     * path differences between crate binary paths (relative) and filesystem paths
     * (absolute), which would cause key mismatches even after volume-prefix stripping.
     */
    private static String normalizeForDedup(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return Normalizer.normalize(filename.toLowerCase(), Normalizer.Form.NFC);
    }

    public void addTrack(String trackPath) {
        String key = normalizeForDedup(trackPath);
        if (normalizedTrackSet.add(key)) {
            // add() returns true only if the key was not already present.
            tracks.add(cdd_sync_binary_utils.resolveSeratoPath(trackPath, database));
        }
    }

    public void addTracks(Collection<String> trackPaths) {
        for (String path : trackPaths) {
            addTrack(path);
        }
    }

    /**
     * Directly sets the track list for an existing crate being rewritten.
     * Bypasses filename-based deduplication — use ONLY when rewriting pre-existing
     * crates where the original track list must be preserved exactly.
     * addTrack() dedup would silently drop tracks sharing the same filename
     * across different folders, which is common in DJ libraries.
     */
    public void setTracksRaw(List<String> rawTracks) {
        this.tracks.clear();
        this.tracks.addAll(rawTracks);
        // Rebuild the dedup set so a subsequent addTrack() call (e.g. Step 3
        // running after Step 2) correctly sees all paths already in the list.
        this.normalizedTrackSet.clear();
        for (String t : rawTracks) {
            this.normalizedTrackSet.add(normalizeForDedup(t));
        }
    }



    public void setVersion(String version) {
        if (version.length() != 4) {
            throw new IllegalStateException("Version should be 4 characters long");
        }
        this.version = version;
    }

    public String getVersion() {
        return version != null ? version : DEFAULT_VERSION;
    }

    public String getSorting() {
        return sorting != null ? sorting : DEFAULT_SORTING;
    }

    public void setSorting(String sorting) {
        this.sorting = sorting;
    }

    public long getSortingRev() {
        return sortingRev != Integer.MIN_VALUE ? sortingRev : DEFAULT_SORTING_REV;
    }

    public void setSortingRev(long sortingRev) {
        this.sortingRev = sortingRev;
    }

    public void addColumn(String columnName) {
        columns.add(columnName);
    }

    public List<String> getColumns() {
        return !columns.isEmpty() ? Collections.unmodifiableList(columns) : Arrays.asList(DEFAULT_COLUMNS);
    }

    public Collection<String> getTracks() {
        return Collections.unmodifiableCollection(tracks);
    }

    public int getTrackCount() {
        return tracks.size();
    }

    /**
     * Reads crate from file using a single unified TLV pass.
     * Each top-level block is read into a byte[] payload before dispatch,
     * so variable-length sub-tags inside ovct/osrt cannot cause stream slippage.
     */
    public static cdd_sync_crate readFrom(File inFile) throws cdd_sync_exception {
        cdd_sync_crate result = new cdd_sync_crate();
        cdd_sync_input_stream in;

        try {
            in = new cdd_sync_input_stream(new BufferedInputStream(new FileInputStream(inFile)));
        } catch (FileNotFoundException e) {
            throw new cdd_sync_exception(e);
        }

        try {
            // --- vrsn header (non-standard: tag + 2-byte literal zero + 8-byte UTF-16 version + rest of block) ---
            in.skipExactString("vrsn");
            in.skipExactByte((byte) 0);
            in.skipExactByte((byte) 0);
            result.setVersion(in.readStringUTF16(8));
            in.skipExactStringUTF16("/Serato ScratchLive Crate");

            // --- single TLV pass over remaining blocks ---
            byte[] tagBytes = new byte[4];
            while (in.read(tagBytes) == 4) {
                String tag = new String(tagBytes);
                int length = in.readIntegerValue();
                byte[] payload = new byte[length];
                in.readFully(payload);

                switch (tag) {
                    case "otrk": {
                        String path = extractPtrk(payload);
                        if (path != null) result.addTrack(path);
                        break;
                    }
                    case "ovct": {
                        String col = extractTvcn(payload);
                        if (col != null) {
                            result.addColumn(col);
                            result.rawOvctPayloads.add(payload); // preserve tvcw etc.
                        }
                        break;
                    }
                    case "osrt": {
                        extractOsrt(payload, result);
                        result.rawOsrtPayload = payload; // preserve brev encoding
                        break;
                    }
                    default:
                        // Unknown block — payload already consumed, nothing to do.
                        break;
                }
            }

        } catch (Exception e) {
            cdd_sync_log.error("Error reading crate file: " + inFile.getAbsolutePath());
            cdd_sync_log.error("Parser failure: " + e.getMessage());
            throw new cdd_sync_exception("Failed to read crate: " + inFile.getName(), e);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }

        return result;
    }

    /**
     * Walks a TLV payload byte[] and returns the value of the first "ptrk" sub-tag
     * decoded as a UTF-16BE string, or null if not found.
     */
    private static String extractPtrk(byte[] payload) throws cdd_sync_exception {
        return walkPayloadForTag(payload, "ptrk");
    }

    /**
     * Walks a TLV payload byte[] and returns the value of the first "tvcn" sub-tag
     * decoded as a UTF-16BE string, or null if not found.
     */
    private static String extractTvcn(byte[] payload) throws cdd_sync_exception {
        return walkPayloadForTag(payload, "tvcn");
    }

    /**
     * Walks an osrt payload byte[] and populates sorting / sortingRev on the crate.
     * Sub-tags: "tvcn" → sorting name (UTF-16BE); "brev" → sortingRev (big-endian, 5 bytes).
     */
    private static void extractOsrt(byte[] payload, cdd_sync_crate target) throws cdd_sync_exception {
        int pos = 0;
        while (pos + 8 <= payload.length) {
            String innerTag = new String(payload, pos, 4);
            int innerLen = readBigEndianInt(payload, pos + 4);
            pos += 8;
            if (pos + innerLen > payload.length) break;

            if ("tvcn".equals(innerTag)) {
                try {
                    target.setSorting(new String(payload, pos, innerLen, "UTF-16BE"));
                } catch (UnsupportedEncodingException e) {
                    throw new cdd_sync_exception(e);
                }
            } else if ("brev".equals(innerTag)) {
                long rev = 0;
                for (int i = 0; i < innerLen; i++) {
                    rev = (rev << 8) | (payload[pos + i] & 0xFF);
                }
                target.setSortingRev(rev);
            }
            pos += innerLen;
        }
    }

    /**
     * Generic TLV walker over a payload byte[].
     * Returns the first value whose 4-byte ASCII tag matches {@code targetTag},
     * decoded as UTF-16BE, or null if the tag is not present.
     */
    private static String walkPayloadForTag(byte[] payload, String targetTag) throws cdd_sync_exception {
        int pos = 0;
        while (pos + 8 <= payload.length) {
            String innerTag = new String(payload, pos, 4);
            int innerLen = readBigEndianInt(payload, pos + 4);
            pos += 8;
            if (pos + innerLen > payload.length) break;

            if (targetTag.equals(innerTag)) {
                try {
                    return new String(payload, pos, innerLen, "UTF-16BE");
                } catch (UnsupportedEncodingException e) {
                    throw new cdd_sync_exception(e);
                }
            }
            pos += innerLen;
        }
        return null;
    }

    /** Reads a big-endian 32-bit int from a byte array at the given offset. */
    private static int readBigEndianInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
             | ((buf[offset + 1] & 0xFF) << 16)
             | ((buf[offset + 2] & 0xFF) << 8)
             |  (buf[offset + 3] & 0xFF);
    }

    /**
     * Writes crate to output stream.
     */
    public void writeTo(OutputStream outStream) throws cdd_sync_exception {
        cdd_sync_output_stream out = new cdd_sync_output_stream(outStream);

        try {
            // Version header
            out.writeBytes("vrsn");
            out.write((byte) 0);
            out.write((byte) 0);
            out.writeUTF16(getVersion());
            out.writeUTF16("/Serato ScratchLive Crate");

            // Sorting — write raw payload verbatim if captured from an existing crate,
            // otherwise reconstruct from model (new crates).
            if (rawOsrtPayload != null) {
                out.writeBytes("osrt");
                out.writeInt(rawOsrtPayload.length);
                out.write(rawOsrtPayload);
            } else {
                out.writeBytes("osrt");
                out.writeInt(getSorting().length() * 2 + 17);
                out.writeBytes("tvcn");
                out.writeInt(getSorting().length() * 2);
                out.writeUTF16(getSorting());
                out.writeBytes("brev");
                out.writeLong(getSortingRev(), 5);
            }

            // Columns — write raw payloads verbatim if captured from an existing crate
            // (preserves tvcw pixel widths set by Serato). Falls back to default
            // reconstruction for new crates where raw payloads are not available.
            if (!rawOvctPayloads.isEmpty()) {
                for (byte[] ovctPayload : rawOvctPayloads) {
                    out.writeBytes("ovct");
                    out.writeInt(ovctPayload.length);
                    out.write(ovctPayload);
                }
            } else {
                for (String column : getColumns()) {
                    out.writeBytes("ovct");
                    out.writeInt(column.length() * 2 + 18);
                    out.writeBytes("tvcn");
                    out.writeInt(column.length() * 2);
                    out.writeUTF16(column);
                    out.writeBytes("tvcw");
                    out.writeInt(2);
                    out.write(0);
                    out.write('0');
                }
            }

            // Tracks
            for (String trackRaw : getTracks()) {
                String track = getUniformTrackName(trackRaw);
                out.writeBytes("otrk");
                out.writeInt(track.length() * 2 + 8);
                out.writeBytes("ptrk");
                out.writeInt(track.length() * 2);
                out.writeUTF16(track);
            }

        } catch (IOException e) {
            throw new cdd_sync_exception(e);
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Normalizes track path for Serato crate format.
     * Does NOT lowercase or NFC-normalize — Serato expects exact case and
     * encoding when writing to crate files. This is intentionally different
     * from the comparison normalization in cdd_sync_binary_utils.normalizePath().
     */
    public static String getUniformTrackName(String name) {
        return cdd_sync_binary_utils.normalizePathForDatabase(name);
    }

    public void writeTo(File outFile) throws cdd_sync_exception {
        try {
            writeTo(new FileOutputStream(outFile));
        } catch (FileNotFoundException e) {
            throw new cdd_sync_exception("Error writing to file " + outFile.getName(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        cdd_sync_crate that = (cdd_sync_crate) o;

        // Compare versions and sorting
        if (getSortingRev() != that.getSortingRev() ||
                !Objects.equals(getVersion(), that.getVersion()) ||
                !Objects.equals(getSorting(), that.getSorting()) ||
                !Objects.equals(getColumns(), that.getColumns())) {
            return false;
        }

        // Compare tracks using NORMALIZED paths
        // In-memory crates often have absolute paths, while on-disk crates have
        // relative.
        // We must normalize both to ensure "smart write" works correctly.
        if (getTrackCount() != that.getTrackCount()) {
            return false;
        }

        Iterator<String> idx1 = getTracks().iterator();
        Iterator<String> idx2 = that.getTracks().iterator();

        while (idx1.hasNext()) {
            String t1 = getUniformTrackName(idx1.next());
            String t2 = getUniformTrackName(idx2.next());
            if (!t1.equals(t2)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        // Hash code must also use normalized tracks to be consistent with equals
        List<String> normalizedTracks = new ArrayList<>(getTrackCount());
        for (String t : getTracks()) {
            normalizedTracks.add(getUniformTrackName(t));
        }
        return Objects.hash(getVersion(), getSorting(), getSortingRev(), getColumns(), normalizedTracks);
    }
}
