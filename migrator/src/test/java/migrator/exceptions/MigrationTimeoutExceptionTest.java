package migrator.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MigrationTimeoutException}.
 */
@DisplayName("MigrationTimeoutException")
class MigrationTimeoutExceptionTest {

    @Nested
    @DisplayName("constructor without cause")
    class ConstructorWithoutCause {

        @Test
        @DisplayName("should store operation and timeout")
        void shouldStoreOperationAndTimeout() {
            MigrationTimeoutException ex = new MigrationTimeoutException("heapWalk", Duration.ofSeconds(30));

            assertThat(ex.getOperation()).isEqualTo("heapWalk");
            assertThat(ex.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("should format message with operation and timeout")
        void shouldFormatMessageWithOperationAndTimeout() {
            MigrationTimeoutException ex = new MigrationTimeoutException("snapshot", Duration.ofMillis(500));

            assertThat(ex.getMessage()).contains("snapshot");
            assertThat(ex.getMessage()).contains("500 ms");
        }

        @Test
        @DisplayName("should have no cause")
        void shouldHaveNoCause() {
            MigrationTimeoutException ex = new MigrationTimeoutException("test", Duration.ofSeconds(1));

            assertThat(ex.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("constructor with cause")
    class ConstructorWithCause {

        @Test
        @DisplayName("should store operation, timeout and cause")
        void shouldStoreOperationTimeoutAndCause() {
            TimeoutException cause = new TimeoutException("timed out");

            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "criticalPhase",
                    Duration.ofSeconds(10),
                    cause
            );

            assertThat(ex.getOperation()).isEqualTo("criticalPhase");
            assertThat(ex.getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("should handle null cause")
        void shouldHandleNullCause() {
            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "test",
                    Duration.ofSeconds(1),
                    null
            );

            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("should preserve InterruptedException as cause")
        void shouldPreserveInterruptedExceptionAsCause() {
            InterruptedException cause = new InterruptedException("interrupted");

            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "test",
                    Duration.ofSeconds(1),
                    cause
            );

            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("inheritance")
    class Inheritance {

        @Test
        @DisplayName("should be unchecked exception (RuntimeException)")
        void shouldBeUncheckedException() {
            assertThat(RuntimeException.class.isAssignableFrom(MigrationTimeoutException.class)).isTrue();
        }

        @Test
        @DisplayName("should not be a checked exception")
        void shouldNotBeACheckedException() {
            // RuntimeException is not a checked exception
            MigrationTimeoutException ex = new MigrationTimeoutException("test", Duration.ofSeconds(1));
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("message formatting")
    class MessageFormatting {

        @Test
        @DisplayName("should show milliseconds in message")
        void shouldShowMillisecondsInMessage() {
            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "operation",
                    Duration.ofSeconds(5)
            );

            assertThat(ex.getMessage()).contains("5000 ms");
        }

        @Test
        @DisplayName("should handle small durations")
        void shouldHandleSmallDurations() {
            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "fast",
                    Duration.ofMillis(10)
            );

            assertThat(ex.getMessage()).contains("10 ms");
        }

        @Test
        @DisplayName("should handle large durations")
        void shouldHandleLargeDurations() {
            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "slow",
                    Duration.ofMinutes(5)
            );

            assertThat(ex.getMessage()).contains("300000 ms");
        }
    }

    @Nested
    @DisplayName("operation names")
    class OperationNames {

        @Test
        @DisplayName("should handle various operation names")
        void shouldHandleVariousOperationNames() {
            String[] operations = {"heapWalk", "heapSnapshot", "criticalPhase", "smokeTest"};

            for (String op : operations) {
                MigrationTimeoutException ex = new MigrationTimeoutException(op, Duration.ofSeconds(1));
                assertThat(ex.getOperation()).isEqualTo(op);
                assertThat(ex.getMessage()).contains(op);
            }
        }

        @Test
        @DisplayName("should handle operation name with special characters")
        void shouldHandleOperationNameWithSpecialCharacters() {
            MigrationTimeoutException ex = new MigrationTimeoutException(
                    "heapSnapshot(MyClass)",
                    Duration.ofSeconds(1)
            );

            assertThat(ex.getOperation()).isEqualTo("heapSnapshot(MyClass)");
            assertThat(ex.getMessage()).contains("heapSnapshot(MyClass)");
        }
    }
}
