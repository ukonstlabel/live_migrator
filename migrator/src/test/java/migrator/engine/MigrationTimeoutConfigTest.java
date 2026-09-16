package migrator.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigrationTimeoutConfig}.
 */
@DisplayName("MigrationTimeoutConfig")
class MigrationTimeoutConfigTest {

    @Nested
    @DisplayName("builder")
    class Builder {

        @Test
        @DisplayName("should create config with default values (no timeouts)")
        void shouldCreateConfigWithDefaultValues() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder().build();

            assertThat(config.heapWalkTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.heapSnapshotTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.criticalPhaseTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.smokeTestTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
        }

        @Test
        @DisplayName("should set heap walk timeout")
        void shouldSetHeapWalkTimeout() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeout(Duration.ofSeconds(30))
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("should set heap walk timeout in seconds")
        void shouldSetHeapWalkTimeoutInSeconds() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeoutSeconds(45)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("should set heap snapshot timeout")
        void shouldSetHeapSnapshotTimeout() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapSnapshotTimeout(Duration.ofMinutes(1))
                    .build();

            assertThat(config.heapSnapshotTimeout()).isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("should set critical phase timeout")
        void shouldSetCriticalPhaseTimeout() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .criticalPhaseTimeout(Duration.ofSeconds(10))
                    .build();

            assertThat(config.criticalPhaseTimeout()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("should set smoke test timeout")
        void shouldSetSmokeTestTimeout() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .smokeTestTimeout(Duration.ofSeconds(5))
                    .build();

            assertThat(config.smokeTestTimeout()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("should set all timeouts at once")
        void shouldSetAllTimeoutsAtOnce() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .allTimeouts(Duration.ofSeconds(60))
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.heapSnapshotTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.criticalPhaseTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.smokeTestTimeout()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("should set all timeouts in seconds")
        void shouldSetAllTimeoutsInSeconds() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .allTimeoutsSeconds(120)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(config.heapSnapshotTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(config.criticalPhaseTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(config.smokeTestTimeout()).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("should reject null timeout in builder")
        void shouldRejectNullTimeoutInBuilder() {
            assertThatThrownBy(() -> MigrationTimeoutConfig.builder().heapWalkTimeout(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should disable timeout when seconds is zero")
        void shouldDisableTimeoutWhenSecondsIsZero() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeoutSeconds(0)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
        }

        @Test
        @DisplayName("should disable timeout when seconds is negative")
        void shouldDisableTimeoutWhenSecondsIsNegative() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeoutSeconds(-5)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("Duration-setter normalization")
    class DurationNormalization {

        @Test
        @DisplayName("should normalize a negative Duration to NO_TIMEOUT")
        void shouldNormalizeNegativeDurationToNoTimeout() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeout(Duration.ofSeconds(-5))
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.heapWalkTimeout().isNegative()).isFalse();
        }

        @Test
        @DisplayName("should normalize a zero Duration to NO_TIMEOUT")
        void shouldNormalizeZeroDurationToNoTimeout() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .criticalPhaseTimeout(Duration.ZERO)
                    .build();

            assertThat(config.criticalPhaseTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
        }

        @Test
        @DisplayName("allTimeouts should normalize a negative Duration to NO_TIMEOUT")
        void allTimeoutsShouldNormalizeNegative() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .allTimeouts(Duration.ofMillis(-1))
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.heapSnapshotTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.criticalPhaseTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
            assertThat(config.smokeTestTimeout()).isEqualTo(MigrationTimeoutConfig.NO_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("configs with identical timeouts should be equal")
        void identicalConfigsAreEqual() {
            MigrationTimeoutConfig a = MigrationTimeoutConfig.builder().heapWalkTimeoutSeconds(30).build();
            MigrationTimeoutConfig b = MigrationTimeoutConfig.builder().heapWalkTimeoutSeconds(30).build();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("configs with different timeouts should not be equal")
        void differentConfigsAreNotEqual() {
            MigrationTimeoutConfig a = MigrationTimeoutConfig.builder().heapWalkTimeoutSeconds(30).build();
            MigrationTimeoutConfig b = MigrationTimeoutConfig.builder().heapWalkTimeoutSeconds(31).build();

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("a negative-Duration config equals a NO_TIMEOUT config (both normalized)")
        void normalizedNegativeEqualsDisabled() {
            MigrationTimeoutConfig negative = MigrationTimeoutConfig.builder()
                    .heapWalkTimeout(Duration.ofSeconds(-1))
                    .build();

            assertThat(negative).isEqualTo(MigrationTimeoutConfig.DEFAULTS);
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("should return false for NO_TIMEOUT")
        void shouldReturnFalseForNoTimeout() {
            assertThat(MigrationTimeoutConfig.isEnabled(MigrationTimeoutConfig.NO_TIMEOUT)).isFalse();
        }

        @Test
        @DisplayName("should return false for zero duration")
        void shouldReturnFalseForZeroDuration() {
            assertThat(MigrationTimeoutConfig.isEnabled(Duration.ZERO)).isFalse();
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            assertThat(MigrationTimeoutConfig.isEnabled(null)).isFalse();
        }

        @Test
        @DisplayName("should return false for negative duration")
        void shouldReturnFalseForNegativeDuration() {
            assertThat(MigrationTimeoutConfig.isEnabled(Duration.ofSeconds(-1))).isFalse();
        }

        @Test
        @DisplayName("should return true for positive duration")
        void shouldReturnTrueForPositiveDuration() {
            assertThat(MigrationTimeoutConfig.isEnabled(Duration.ofSeconds(1))).isTrue();
            assertThat(MigrationTimeoutConfig.isEnabled(Duration.ofMillis(100))).isTrue();
            assertThat(MigrationTimeoutConfig.isEnabled(Duration.ofMinutes(5))).isTrue();
        }
    }

    @Nested
    @DisplayName("DEFAULTS")
    class Defaults {

        @Test
        @DisplayName("should have all timeouts disabled")
        void shouldHaveAllTimeoutsDisabled() {
            MigrationTimeoutConfig defaults = MigrationTimeoutConfig.DEFAULTS;

            assertThat(MigrationTimeoutConfig.isEnabled(defaults.heapWalkTimeout())).isFalse();
            assertThat(MigrationTimeoutConfig.isEnabled(defaults.heapSnapshotTimeout())).isFalse();
            assertThat(MigrationTimeoutConfig.isEnabled(defaults.criticalPhaseTimeout())).isFalse();
            assertThat(MigrationTimeoutConfig.isEnabled(defaults.smokeTestTimeout())).isFalse();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("should show disabled for NO_TIMEOUT values")
        void shouldShowDisabledForNoTimeoutValues() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.DEFAULTS;

            String str = config.toString();

            assertThat(str).contains("disabled");
        }

        @Test
        @DisplayName("should show milliseconds for enabled values")
        void shouldShowMillisecondsForEnabledValues() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeout(Duration.ofSeconds(30))
                    .build();

            String str = config.toString();

            assertThat(str).contains("30000ms");
        }
    }

    @Nested
    @DisplayName("method chaining")
    class MethodChaining {

        @Test
        @DisplayName("should support fluent builder pattern")
        void shouldSupportFluentBuilderPattern() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeoutSeconds(60)
                    .heapSnapshotTimeoutSeconds(30)
                    .criticalPhaseTimeoutSeconds(20)
                    .smokeTestTimeoutSeconds(10)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.heapSnapshotTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.criticalPhaseTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(config.smokeTestTimeout()).isEqualTo(Duration.ofSeconds(10));
        }
    }
}
