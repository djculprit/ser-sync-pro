import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cdd_sync_crate read/write operations.
 */
class cdd_sync_crate_test {

    @Test
    void readWriteRoundTrip() throws Exception {
        cdd_sync_crate original = new cdd_sync_crate();
        original.addTrack("Music/test/track.mp3");
        original.addTrack("Music/test/track2.flac");

        java.io.File tmp = java.io.File.createTempFile("crate_test", ".crate");
        tmp.deleteOnExit();
        original.writeTo(tmp);

        cdd_sync_crate loaded = cdd_sync_crate.readFrom(tmp);
        assertEquals(new java.util.ArrayList<>(original.getTracks()),
                new java.util.ArrayList<>(loaded.getTracks()));
    }

    @Test
    void readWriteRoundTrip_emptyCrate() throws Exception {
        cdd_sync_crate original = new cdd_sync_crate();

        java.io.File tmp = java.io.File.createTempFile("crate_test_empty", ".crate");
        tmp.deleteOnExit();
        original.writeTo(tmp);

        cdd_sync_crate loaded = cdd_sync_crate.readFrom(tmp);
        assertEquals(new java.util.ArrayList<>(original.getTracks()),
                new java.util.ArrayList<>(loaded.getTracks()));
        assertEquals(0, loaded.getTrackCount());
    }

    @Test
    void readWriteRoundTrip_preservesTrackOrder() throws Exception {
        cdd_sync_crate original = new cdd_sync_crate();
        original.addTrack("Music/aaa.mp3");
        original.addTrack("Music/zzz.mp3");
        original.addTrack("Music/mmm.mp3");

        java.io.File tmp = java.io.File.createTempFile("crate_test_order", ".crate");
        tmp.deleteOnExit();
        original.writeTo(tmp);

        cdd_sync_crate loaded = cdd_sync_crate.readFrom(tmp);
        assertEquals(new java.util.ArrayList<>(original.getTracks()),
                new java.util.ArrayList<>(loaded.getTracks()));
    }
}
