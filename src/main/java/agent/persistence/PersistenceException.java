package agent.persistence;

/**
 * Runtime wrapper for persistence read/write failures.
 *
 * <p>The agent treats persistence as best-effort, but callers still need to
 * distinguish "no rows" from "SQLite failed". This exception lets background
 * workers count the failure and lets HTTP routes return an explicit error.
 */
public final class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
