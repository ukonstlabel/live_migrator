package migrator.engine;

import migrator.exceptions.MigrationTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Utility for executing operations with configurable timeouts.
 *
 * <p>This class provides methods to wrap synchronous operations with timeout protection.
 * If an operation exceeds its timeout, a {@link MigrationTimeoutException} is thrown.
 *
 * <p>The executor uses a cached thread pool for timeout operations. Operations that
 * timeout are interrupted, though the underlying operation may not respond to interruption
 * depending on its implementation.
 *
 * @see MigrationTimeoutConfig
 * @see MigrationTimeoutException
 */
public final class TimeoutExecutor {

    private static final Logger log = LoggerFactory.getLogger(TimeoutExecutor.class);

    // Shared executor for timeout operations - daemon threads so they don't prevent JVM shutdown
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "migration-timeout-executor");
        t.setDaemon(true);
        return t;
    });

    private TimeoutExecutor() {
        // Utility class
    }

    /**
     * Executes a supplier with a timeout.
     *
     * <p>If the timeout is disabled ({@link MigrationTimeoutConfig#NO_TIMEOUT} or null),
     * the operation is executed directly without timeout protection.
     *
     * @param operation the name of the operation (for error messages)
     * @param timeout the timeout duration, or null/zero to disable
     * @param supplier the operation to execute
     * @param <T> the return type
     * @return the result of the supplier
     * @throws MigrationTimeoutException if the operation times out
     * @throws RuntimeException if the operation throws an exception
     */
    public static <T> T executeWithTimeout(String operation, Duration timeout, Supplier<T> supplier) {
        if (!MigrationTimeoutConfig.isEnabled(timeout)) {
            return supplier.get();
        }

        log.debug("Executing '{}' with timeout of {} ms", operation, timeout.toMillis());

        // Submit to the ExecutorService (not CompletableFuture.supplyAsync): only a Future from
        // submit() honours cancel(true) by interrupting the worker thread, so a timed-out operation
        // is actually signalled to stop rather than left running concurrently with rollback.
        Future<T> future = EXECUTOR.submit((Callable<T>) supplier::get);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Operation '{}' timed out after {} ms", operation, timeout.toMillis());
            throw new MigrationTimeoutException(operation, timeout, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new MigrationTimeoutException(operation, timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error err) {
                throw err;
            } else if (cause instanceof RuntimeException re) {
                throw re;
            } else {
                throw new RuntimeException("Operation '" + operation + "' failed", cause);
            }
        }
    }

    /**
     * Executes a runnable with a timeout.
     *
     * <p>If the timeout is disabled ({@link MigrationTimeoutConfig#NO_TIMEOUT} or null),
     * the operation is executed directly without timeout protection.
     *
     * @param operation the name of the operation (for error messages)
     * @param timeout the timeout duration, or null/zero to disable
     * @param runnable the operation to execute
     * @throws MigrationTimeoutException if the operation times out
     * @throws RuntimeException if the operation throws an exception
     */
    public static void executeWithTimeout(String operation, Duration timeout, Runnable runnable) {
        executeWithTimeout(operation, timeout, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Executes a callable with a timeout.
     *
     * <p>If the timeout is disabled ({@link MigrationTimeoutConfig#NO_TIMEOUT} or null),
     * the operation is executed directly without timeout protection.
     *
     * @param operation the name of the operation (for error messages)
     * @param timeout the timeout duration, or null/zero to disable
     * @param callable the operation to execute
     * @param <T> the return type
     * @return the result of the callable
     * @throws MigrationTimeoutException if the operation times out
     * @throws Exception if the callable throws a checked exception
     */
    public static <T> T executeWithTimeoutChecked(String operation, Duration timeout, Callable<T> callable) throws Exception {
        if (!MigrationTimeoutConfig.isEnabled(timeout)) {
            return callable.call();
        }

        log.debug("Executing '{}' with timeout of {} ms (checked)", operation, timeout.toMillis());

        // submit() runs the Callable directly (checked exceptions propagate as the ExecutionException
        // cause) and gives a Future whose cancel(true) interrupts the worker on timeout.
        Future<T> future = EXECUTOR.submit(callable);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Operation '{}' timed out after {} ms", operation, timeout.toMillis());
            throw new MigrationTimeoutException(operation, timeout, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new MigrationTimeoutException(operation, timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error err) {
                throw err;
            } else if (cause instanceof Exception ex) {
                throw ex;
            } else {
                throw new RuntimeException("Operation '" + operation + "' failed", cause);
            }
        }
    }

    /**
     * Executes a void operation that may throw checked exceptions with a timeout.
     *
     * @param operation the name of the operation (for error messages)
     * @param timeout the timeout duration, or null/zero to disable
     * @param action the operation to execute
     * @throws MigrationTimeoutException if the operation times out
     * @throws Exception if the action throws a checked exception
     */
    public static void executeWithTimeoutChecked(String operation, Duration timeout, CheckedRunnable action) throws Exception {
        executeWithTimeoutChecked(operation, timeout, () -> {
            action.run();
            return null;
        });
    }

    /**
     * A runnable that can throw checked exceptions.
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
