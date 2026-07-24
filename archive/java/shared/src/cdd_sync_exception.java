/**
 * Exception for Serato sync library operations.
 */
public class cdd_sync_exception extends Exception {

    public cdd_sync_exception(String message) {
        super(message);
    }

    public cdd_sync_exception(Throwable cause) {
        super(cause);
    }

    public cdd_sync_exception(String message, Throwable cause) {
        super(message, cause);
    }
}
