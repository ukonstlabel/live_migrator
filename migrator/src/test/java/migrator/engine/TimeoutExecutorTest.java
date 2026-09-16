package migrator.engine;

import migrator.exceptions.MigrationTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TimeoutExecutor}.
 */
@DisplayName("TimeoutExecutor")
class TimeoutExecutorTest {

    @Nested
    @DisplayName("executeWithTimeout (Supplier)")
    class ExecuteWithTimeoutSupplier {

        @Test
        @DisplayName("should return result when operation completes within timeout")
        void shouldReturnResultWhenOperationCompletesWithinTimeout() {
            String result = TimeoutExecutor.executeWithTimeout(
                    "test",
                    Duration.ofSeconds(5),
                    () -> "success"
            );

            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("should throw MigrationTimeoutException when operation exceeds timeout")
        void shouldThrowMigrationTimeoutExceptionWhenOperationExceedsTimeout() {
            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeout(
                    "slowOperation",
                    Duration.ofMillis(50),
                    () -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "never";
                    }
            ))
                    .isInstanceOf(MigrationTimeoutException.class)
                    .hasMessageContaining("slowOperation")
                    .hasMessageContaining("50 ms");
        }

        @Test
        @DisplayName("should execute without timeout when timeout is disabled")
        void shouldExecuteWithoutTimeoutWhenTimeoutIsDisabled() {
            String result = TimeoutExecutor.executeWithTimeout(
                    "test",
                    MigrationTimeoutConfig.NO_TIMEOUT,
                    () -> "noTimeout"
            );

            assertThat(result).isEqualTo("noTimeout");
        }

        @Test
        @DisplayName("should execute without timeout when timeout is null")
        void shouldExecuteWithoutTimeoutWhenTimeoutIsNull() {
            String result = TimeoutExecutor.executeWithTimeout(
                    "test",
                    null,
                    () -> "nullTimeout"
            );

            assertThat(result).isEqualTo("nullTimeout");
        }

        @Test
        @DisplayName("should propagate RuntimeException from supplier")
        void shouldPropagateRuntimeExceptionFromSupplier() {
            RuntimeException expected = new IllegalStateException("test error");

            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeout(
                    "test",
                    Duration.ofSeconds(5),
                    () -> {
                        throw expected;
                    }
            ))
                    .isSameAs(expected);
        }

        @Test
        @DisplayName("should propagate Error from supplier")
        void shouldPropagateErrorFromSupplier() {
            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeout(
                    "test",
                    Duration.ofSeconds(5),
                    () -> {
                        throw new OutOfMemoryError("test OOM");
                    }
            ))
                    .isInstanceOf(OutOfMemoryError.class)
                    .hasMessage("test OOM");
        }

        @Test
        @DisplayName("should interrupt the worker thread when the operation times out")
        void shouldInterruptWorkerThreadOnTimeout() throws InterruptedException {
            AtomicBoolean interrupted = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeout(
                    "interruptible",
                    Duration.ofMillis(50),
                    () -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            interrupted.set(true); // worker actually received the interrupt
                        } finally {
                            done.countDown();
                        }
                        return "never";
                    }
            )).isInstanceOf(MigrationTimeoutException.class);

            // The worker must observe the interrupt promptly, not run the full 5s sleep.
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.get()).isTrue();
        }

        @Test
        @DisplayName("timeout exception should contain operation name")
        void timeoutExceptionShouldContainOperationName() {
            try {
                TimeoutExecutor.executeWithTimeout(
                        "myCustomOperation",
                        Duration.ofMillis(10),
                        () -> {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        }
                );
            } catch (MigrationTimeoutException e) {
                assertThat(e.getOperation()).isEqualTo("myCustomOperation");
                assertThat(e.getTimeout()).isEqualTo(Duration.ofMillis(10));
            }
        }
    }

    @Nested
    @DisplayName("executeWithTimeout (Runnable)")
    class ExecuteWithTimeoutRunnable {

        @Test
        @DisplayName("should complete when operation finishes within timeout")
        void shouldCompleteWhenOperationFinishesWithinTimeout() {
            AtomicBoolean executed = new AtomicBoolean(false);

            TimeoutExecutor.executeWithTimeout(
                    "test",
                    Duration.ofSeconds(5),
                    () -> executed.set(true)
            );

            assertThat(executed).isTrue();
        }

        @Test
        @DisplayName("should throw MigrationTimeoutException when runnable exceeds timeout")
        void shouldThrowMigrationTimeoutExceptionWhenRunnableExceedsTimeout() {
            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeout(
                    "slowRunnable",
                    Duration.ofMillis(50),
                    () -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
            ))
                    .isInstanceOf(MigrationTimeoutException.class);
        }
    }

    @Nested
    @DisplayName("executeWithTimeoutChecked")
    class ExecuteWithTimeoutChecked {

        @Test
        @DisplayName("should return result when operation completes within timeout")
        void shouldReturnResultWhenOperationCompletesWithinTimeout() throws Exception {
            String result = TimeoutExecutor.executeWithTimeoutChecked(
                    "test",
                    Duration.ofSeconds(5),
                    () -> "checked"
            );

            assertThat(result).isEqualTo("checked");
        }

        @Test
        @DisplayName("should throw checked exception from callable")
        void shouldThrowCheckedExceptionFromCallable() {
            Exception expected = new Exception("checked exception");

            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeoutChecked(
                    "test",
                    Duration.ofSeconds(5),
                    () -> {
                        throw expected;
                    }
            ))
                    .isSameAs(expected);
        }

        @Test
        @DisplayName("should throw MigrationTimeoutException when callable exceeds timeout")
        void shouldThrowMigrationTimeoutExceptionWhenCallableExceedsTimeout() {
            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeoutChecked(
                    "slowCallable",
                    Duration.ofMillis(50),
                    () -> {
                        Thread.sleep(5000);
                        return "never";
                    }
            ))
                    .isInstanceOf(MigrationTimeoutException.class);
        }

        @Test
        @DisplayName("should execute without timeout when timeout is disabled")
        void shouldExecuteWithoutTimeoutWhenTimeoutIsDisabled() throws Exception {
            String result = TimeoutExecutor.executeWithTimeoutChecked(
                    "test",
                    MigrationTimeoutConfig.NO_TIMEOUT,
                    () -> "noTimeout"
            );

            assertThat(result).isEqualTo("noTimeout");
        }
    }

    @Nested
    @DisplayName("executeWithTimeoutChecked (CheckedRunnable)")
    class ExecuteWithTimeoutCheckedRunnable {

        @Test
        @DisplayName("should complete when operation finishes within timeout")
        void shouldCompleteWhenOperationFinishesWithinTimeout() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);

            TimeoutExecutor.executeWithTimeoutChecked(
                    "test",
                    Duration.ofSeconds(5),
                    counter::incrementAndGet
            );

            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should throw checked exception from runnable")
        void shouldThrowCheckedExceptionFromRunnable() {
            Exception expected = new Exception("runnable failed");

            assertThatThrownBy(() -> TimeoutExecutor.executeWithTimeoutChecked(
                    "test",
                    Duration.ofSeconds(5),
                    () -> {
                        throw expected;
                    }
            ))
                    .isSameAs(expected);
        }
    }

    @Nested
    @DisplayName("concurrent execution")
    class ConcurrentExecution {

        @Test
        @DisplayName("should handle multiple concurrent timeouts")
        void shouldHandleMultipleConcurrentTimeouts() throws InterruptedException {
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger timeoutCount = new AtomicInteger(0);

            Thread[] threads = new Thread[10];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        TimeoutExecutor.executeWithTimeout(
                                "concurrent-" + index,
                                Duration.ofMillis(100),
                                () -> {
                                    try {
                                        // Half will complete, half will timeout
                                        Thread.sleep(index < 5 ? 10 : 5000);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "done";
                                }
                        );
                        successCount.incrementAndGet();
                    } catch (MigrationTimeoutException e) {
                        timeoutCount.incrementAndGet();
                    }
                });
                threads[i].start();
            }

            for (Thread thread : threads) {
                thread.join();
            }

            assertThat(successCount.get()).isEqualTo(5);
            assertThat(timeoutCount.get()).isEqualTo(5);
        }
    }
}
