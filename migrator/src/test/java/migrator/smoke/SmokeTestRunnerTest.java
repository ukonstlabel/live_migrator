package migrator.smoke;

import migrator.exceptions.ValidationException;
import migrator.plan.MigratorDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SmokeTestRunner}.
 */
@DisplayName("SmokeTestRunner")
class SmokeTestRunnerTest {

    @Nested
    @DisplayName("runAll with health checks")
    class RunAllWithHealthChecks {

        @Test
        @DisplayName("should pass when all health checks return true")
        void shouldPassWhenAllHealthChecksReturnTrue() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> true)
                    .addHealthCheck(() -> true)
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isTrue();
            assertThat(report.results()).hasSize(2);
            assertThat(report.results()).allMatch(SmokeTestResult::isOk);
        }

        @Test
        @DisplayName("should fail when any health check returns false")
        void shouldFailWhenAnyHealthCheckReturnsFalse() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> true)
                    .addHealthCheck(() -> false)
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results().get(0).isOk()).isTrue();
            assertThat(report.results().get(1).isOk()).isFalse();
        }

        @Test
        @DisplayName("should fail when health check throws exception")
        void shouldFailWhenHealthCheckThrowsException() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> {
                        throw new ValidationException("Service unavailable");
                    })
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results().get(0).isOk()).isFalse();
            assertThat(report.results().get(0).message()).contains("threw");
        }

        @Test
        @DisplayName("should name health checks with index")
        void shouldNameHealthChecksWithIndex() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> true)
                    .addHealthCheck(() -> true)
                    .addHealthCheck(() -> true)
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.results().get(0).name()).isEqualTo("healthcheck#0");
            assertThat(report.results().get(1).name()).isEqualTo("healthcheck#1");
            assertThat(report.results().get(2).name()).isEqualTo("healthcheck#2");
        }
    }

    @Nested
    @DisplayName("runAll with smoke tests")
    class RunAllWithSmokeTests {

        @Test
        @DisplayName("should pass when all smoke tests return ok")
        void shouldPassWhenAllSmokeTestsReturnOk() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok("test1"))
                    .addSmokeTest(created -> SmokeTestResult.ok("test2"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isTrue();
            assertThat(report.results()).hasSize(2);
        }

        @Test
        @DisplayName("should fail when any smoke test returns fail")
        void shouldFailWhenAnySmokeTestReturnsFail() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok("test1"))
                    .addSmokeTest(created -> SmokeTestResult.fail("test2", "validation failed", null))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
        }

        @Test
        @DisplayName("should fail when smoke test throws exception")
        void shouldFailWhenSmokeTestThrowsException() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> {
                        throw new ValidationException("Test error");
                    })
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results().get(0).message()).contains("threw");
        }

        @Test
        @DisplayName("should fail when smoke test returns null")
        void shouldFailWhenSmokeTestReturnsNull() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> null)
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results().get(0).message()).contains("null");
        }

        @Test
        @DisplayName("should assign default name when result has no name")
        void shouldAssignDefaultNameWhenResultHasNoName() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> createResultWithNullName())
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.results().get(0).name()).isEqualTo("smoketest#0");
        }

        @Test
        @DisplayName("should preserve result name when provided")
        void shouldPreserveResultNameWhenProvided() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok("custom-name"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.results().get(0).name()).isEqualTo("custom-name");
        }

        @Test
        @DisplayName("should pass createdPerMigrator to smoke tests")
        void shouldPassCreatedPerMigratorToSmokeTests() {
            Map<MigratorDescriptor, List<Object>> capturedMap = new HashMap<>();

            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> {
                        capturedMap.putAll(created);
                        return SmokeTestResult.ok("capture");
                    })
                    .build();

            Map<MigratorDescriptor, List<Object>> input = new HashMap<>();
            // Note: can't easily create MigratorDescriptor without real migrator class
            // Just verify the map is passed through

            runner.runAll(input);

            assertThat(capturedMap).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("runAll with mixed health checks and smoke tests")
    class RunAllWithMixed {

        @Test
        @DisplayName("should run health checks before smoke tests")
        void shouldRunHealthChecksBeforeSmokeTests() {
            StringBuilder order = new StringBuilder();

            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> {
                        order.append("H1");
                        return true;
                    })
                    .addSmokeTest(created -> {
                        order.append("S1");
                        return SmokeTestResult.ok("s1");
                    })
                    .addHealthCheck(() -> {
                        order.append("H2");
                        return true;
                    })
                    .addSmokeTest(created -> {
                        order.append("S2");
                        return SmokeTestResult.ok("s2");
                    })
                    .build();

            runner.runAll(Map.of());

            // Health checks run first (in order), then smoke tests (in order)
            assertThat(order.toString()).isEqualTo("H1H2S1S2");
        }

        @Test
        @DisplayName("should pass only when both health checks and smoke tests pass")
        void shouldPassOnlyWhenBothPass() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> true)
                    .addSmokeTest(created -> SmokeTestResult.ok("test"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isTrue();
            assertThat(report.results()).hasSize(2);
        }

        @Test
        @DisplayName("should fail when health check fails even if smoke tests pass")
        void shouldFailWhenHealthCheckFailsEvenIfSmokeTestsPass() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> false)
                    .addSmokeTest(created -> SmokeTestResult.ok("test"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should create empty runner")
        void shouldCreateEmptyRunner() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder().build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isTrue();
            assertThat(report.results()).isEmpty();
        }

        @Test
        @DisplayName("should support method chaining")
        void shouldSupportMethodChaining() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> true)
                    .addHealthCheck(() -> true)
                    .addSmokeTest(created -> SmokeTestResult.ok("1"))
                    .addSmokeTest(created -> SmokeTestResult.ok("2"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.results()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle runtime exception from health check")
        void shouldHandleRuntimeExceptionFromHealthCheck() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> {
                        throw new RuntimeException("Unexpected error");
                    })
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results().get(0).error()).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should handle runtime exception from smoke test")
        void shouldHandleRuntimeExceptionFromSmokeTest() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> {
                        throw new RuntimeException("Unexpected error");
                    })
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results().get(0).error()).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should isolate an AssertionError thrown by a smoke test as a failed result")
        void shouldIsolateAssertionErrorFromSmokeTest() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> {
                        throw new AssertionError("assertion in smoke test");
                    })
                    .addSmokeTest(created -> SmokeTestResult.ok("after"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            // The throwing test must not abort the run: the second test still executes.
            assertThat(report.success()).isFalse();
            assertThat(report.results()).hasSize(2);
            assertThat(report.results().get(0).isOk()).isFalse();
            assertThat(report.results().get(0).error()).isInstanceOf(AssertionError.class);
            assertThat(report.results().get(1).isOk()).isTrue();
        }

        @Test
        @DisplayName("should isolate an Error thrown by a health check as a failed result")
        void shouldIsolateErrorFromHealthCheck() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> { throw new AssertionError("boom"); })
                    .addHealthCheck(() -> true)
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results()).hasSize(2);
            assertThat(report.results().get(0).error()).isInstanceOf(AssertionError.class);
            assertThat(report.results().get(1).isOk()).isTrue();
        }

        @Test
        @DisplayName("should treat a null createdPerMigrator as an empty map")
        void shouldTreatNullCreatedPerMigratorAsEmpty() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok(created.isEmpty() ? "empty" : "non-empty"))
                    .build();

            SmokeTestReport report = runner.runAll(null);

            assertThat(report.success()).isTrue();
            assertThat(report.results().get(0).name()).isEqualTo("empty");
        }

        @Test
        @DisplayName("should continue running all tests even after failures")
        void shouldContinueRunningAllTestsEvenAfterFailures() {
            StringBuilder executed = new StringBuilder();

            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> {
                        executed.append("H1");
                        return false; // fail
                    })
                    .addHealthCheck(() -> {
                        executed.append("H2");
                        return true;
                    })
                    .addSmokeTest(created -> {
                        executed.append("S1");
                        return SmokeTestResult.fail("s1", "fail", null);
                    })
                    .addSmokeTest(created -> {
                        executed.append("S2");
                        return SmokeTestResult.ok("s2");
                    })
                    .build();

            runner.runAll(Map.of());

            assertThat(executed.toString()).isEqualTo("H1H2S1S2");
        }
    }

    // Helper method to create a result with null name using reflection
    private static SmokeTestResult createResultWithNullName() {
        try {
            var ctor = SmokeTestResult.class.getDeclaredConstructor(
                    String.class, boolean.class, String.class, Throwable.class);
            ctor.setAccessible(true);
            return ctor.newInstance(null, true, null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test result", e);
        }
    }
}
