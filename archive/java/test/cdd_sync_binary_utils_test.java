import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cdd_sync_binary_utils.
 */
class cdd_sync_binary_utils_test {

    @Test
    void readInt_parsesPositiveValue() {
        byte[] data = { 0x00, 0x00, 0x01, 0x00 }; // 256
        assertEquals(256, cdd_sync_binary_utils.readInt(data, 0));
    }

    @Test
    void readInt_parsesMaxValue() {
        byte[] data = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        assertEquals(-1, cdd_sync_binary_utils.readInt(data, 0)); // Two's complement
    }

    @Test
    void readInt_parsesAtOffset() {
        byte[] data = { 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00 };
        assertEquals(0x00020000, cdd_sync_binary_utils.readInt(data, 4));
    }

    @Test
    void getFilename_extractsFromUnixPath() {
        assertEquals("track.mp3", cdd_sync_binary_utils.getFilename("/Music/DJ/track.mp3"));
    }

    @Test
    void getFilename_extractsFromWindowsPath() {
        assertEquals("track.mp3", cdd_sync_binary_utils.getFilename("C:\\Music\\DJ\\track.mp3"));
    }

    @Test
    void getFilename_lowercases() {
        assertEquals("track.mp3", cdd_sync_binary_utils.getFilename("/Music/TRACK.MP3"));
    }

    @Test
    void getFilename_handlesNull() {
        assertEquals("", cdd_sync_binary_utils.getFilename(null));
    }

    @Test
    void getFilename_handlesNoSlash() {
        assertEquals("track.mp3", cdd_sync_binary_utils.getFilename("track.mp3"));
    }

    @Test
    void getRawFilename_preservesCase() {
        assertEquals("TRACK.MP3", cdd_sync_binary_utils.getRawFilename("/Music/TRACK.MP3"));
    }

    @Test
    void getRawFilename_handlesNull() {
        assertEquals("", cdd_sync_binary_utils.getRawFilename(null));
    }

    @Test
    void formatSize_bytes() {
        assertEquals("512 B", cdd_sync_binary_utils.formatSize(512));
    }

    @Test
    void formatSize_kilobytes() {
        String result = cdd_sync_binary_utils.formatSize(2048);
        assertTrue(result.contains("KB"), "Expected KB but got: " + result);
    }

    @Test
    void formatSize_megabytes() {
        String result = cdd_sync_binary_utils.formatSize(5 * 1024 * 1024);
        assertTrue(result.contains("MB"), "Expected MB but got: " + result);
    }

    @Test
    void formatSize_gigabytes() {
        String result = cdd_sync_binary_utils.formatSize(2L * 1024 * 1024 * 1024);
        assertTrue(result.contains("GB"), "Expected GB but got: " + result);
    }

    @Test
    void readFile_writeFile_roundTrip() throws Exception {
        java.io.File tmp = java.io.File.createTempFile("cdd_sync_test", ".bin");
        tmp.deleteOnExit();

        byte[] expected = { 0x01, 0x02, 0x03, 0x04, 0x05 };
        cdd_sync_binary_utils.writeFile(tmp, expected);

        byte[] actual = cdd_sync_binary_utils.readFile(tmp);
        assertArrayEquals(expected, actual);
    }

    // ==================== normalizePath tests ====================

    @Test
    void normalizePath_stripsVolumesPrefix() {
        assertEquals("music/dj/track.mp3",
                cdd_sync_binary_utils.normalizePath("/Volumes/DriveName/Music/DJ/track.mp3"));
    }

    @Test
    void normalizePath_stripsWindowsDriveLetter() {
        assertEquals("music/dj/track.mp3",
                cdd_sync_binary_utils.normalizePath("C:\\Music\\DJ\\track.mp3"));
    }

    @Test
    void normalizePath_normalizesNfcUnicode() {
        // NFD: e + combining acute accent (U+0301)
        String nfd = "caf\u0065\u0301.mp3";
        // NFC: é (U+00E9)
        String nfc = "caf\u00e9.mp3";
        assertEquals(cdd_sync_binary_utils.normalizePath(nfd),
                cdd_sync_binary_utils.normalizePath(nfc));
    }

    @Test
    void normalizePath_handlesNull() {
        assertEquals("", cdd_sync_binary_utils.normalizePath(null));
    }

    @Test
    void normalizePath_lowercases() {
        assertEquals("music/track.mp3",
                cdd_sync_binary_utils.normalizePath("Music/TRACK.MP3"));
    }

    // ==================== normalizePathForDatabase tests ====================

    @Test
    void normalizePathForDatabase_preservesCase() {
        assertEquals("Music/DJ/Track.mp3",
                cdd_sync_binary_utils.normalizePathForDatabase("/Volumes/Drive/Music/DJ/Track.mp3"));
    }

    @Test
    void normalizePathForDatabase_stripsWindowsDrive() {
        assertEquals("Music/DJ/Track.mp3",
                cdd_sync_binary_utils.normalizePathForDatabase("D:\\Music\\DJ\\Track.mp3"));
    }

    @Test
    void normalizePathForDatabase_handlesNull() {
        assertEquals("", cdd_sync_binary_utils.normalizePathForDatabase(null));
    }
}
