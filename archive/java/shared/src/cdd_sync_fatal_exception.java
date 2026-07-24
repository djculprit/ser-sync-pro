/**
 * Fatal exception thrown when the application encounters an unrecoverable
 * error.
 * Replaces System.exit() calls to allow proper cleanup and testability.
 */
public class cdd_sync_fatal_exception extends RuntimeException {

    public cdd_sync_fatal_exception(String message) {
        super(message);
    }

    public cdd_sync_fatal_exception(String message, Throwable cause) {
        super(message, cause);
    }
}
