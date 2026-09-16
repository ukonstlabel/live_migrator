package migrator.exceptions;

import java.time.Duration;

/**
 * Exception thrown when a migration operation exceeds its configured timeout.
 *
 * <p>This exception indicates that an operation (heap walk, snapshot, critical phase,
 * or smoke test) did not complete within the allowed time limit. The migration
 * should be considered failed and rollback may be necessary.
 *
 * <p>This is an unchecked exception (extends RuntimeException) to allow timeout
 * protection to be added to existing code without changing method signatures.
 * Callers should catch this exception at appropriate points to handle timeouts.
 *
 * @see migrator.engine.MigrationTimeoutConfig
 */
public class MigrationTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final Duration timeout;

    /**
     * Creates a new timeout exception.
     *
     * @param operation the name of the operation that timed out
     * @param timeout the configured timeout that was exceeded
     */
    public MigrationTimeoutException(String operation, Duration timeout) {
        super(formatMessage(operation, timeout));
        this.operation = operation;
        this.timeout = timeout;
    }

    /**
     * Creates a new timeout exception with a cause.
     *
     * @param operation the name of the operation that timed out
     * @param timeout the configured timeout that was exceeded
     * @param cause the underlying cause (typically TimeoutException or InterruptedException)
     */
    public MigrationTimeoutException(String operation, Duration timeout, Throwable cause) {
        super(formatMessage(operation, timeout), cause);
        this.operation = operation;
        this.timeout = timeout;
    }

    /**
     * Returns the name of the operation that timed out.
     *
     * @return the operation name (e.g., "heapWalk", "snapshot", "criticalPhase", "smokeTest")
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Returns the configured timeout that was exceeded.
     *
     * @return the timeout duration
     */
    public Duration getTimeout() {
        return timeout;
    }

    private static String formatMessage(String operation, Duration timeout) {
        return String.format("Operation '%s' timed out after %d ms", operation, timeout.toMillis());
    }
}
