package migrator.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SmokeTestReport}.
 */
@DisplayName("SmokeTestReport")
class SmokeTestReportTest {

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("should create successful report with results")
        void shouldCreateSuccessfulReportWithResults() {
            List<SmokeTestResult> results = List.of(
                    SmokeTestResult.ok("test1"),
                    SmokeTestResult.ok("test2")
            );

            SmokeTestReport report = new SmokeTestReport(true, results);

            assertThat(report.success()).isTrue();
            assertThat(report.results()).hasSize(2);
        }

        @Test
        @DisplayName("should create failed report with results")
        void shouldCreateFailedReportWithResults() {
            List<SmokeTestResult> results = List.of(
                    SmokeTestResult.ok("test1"),
                    SmokeTestResult.fail("test2", "failed", null)
            );

            SmokeTestReport report = new SmokeTestReport(false, results);

            assertThat(report.success()).isFalse();
            assertThat(report.results()).hasSize(2);
        }

        @Test
        @DisplayName("should create report with empty results")
        void shouldCreateReportWithEmptyResults() {
            SmokeTestReport report = new SmokeTestReport(true, List.of());

            assertThat(report.success()).isTrue();
            assertThat(report.results()).isEmpty();
        }

        @Test
        @DisplayName("should make defensive copy of results")
        void shouldMakeDefensiveCopyOfResults() {
            List<SmokeTestResult> mutableList = new ArrayList<>();
            mutableList.add(SmokeTestResult.ok("test1"));

            SmokeTestReport report = new SmokeTestReport(true, mutableList);

            mutableList.add(SmokeTestResult.ok("test2"));

            assertThat(report.results()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("results")
    class Results {

        @Test
        @DisplayName("should return unmodifiable list")
        void shouldReturnUnmodifiableList() {
            SmokeTestReport report = new SmokeTestReport(true, List.of(
                    SmokeTestResult.ok("test")
            ));

            List<SmokeTestResult> results = report.results();

            assertThatThrownBy(() -> results.add(SmokeTestResult.ok("new")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("should preserve result order")
        void shouldPreserveResultOrder() {
            List<SmokeTestResult> results = List.of(
                    SmokeTestResult.ok("first"),
                    SmokeTestResult.ok("second"),
                    SmokeTestResult.ok("third")
            );

            SmokeTestReport report = new SmokeTestReport(true, results);

            assertThat(report.results().get(0).name()).isEqualTo("first");
            assertThat(report.results().get(1).name()).isEqualTo("second");
            assertThat(report.results().get(2).name()).isEqualTo("third");
        }
    }

    @Nested
    @DisplayName("success flag")
    class SuccessFlag {

        @Test
        @DisplayName("success true should not be affected by result statuses")
        void successTrueShouldNotBeAffectedByResultStatuses() {
            // Report says success=true even though a result failed
            // (This tests that the flag is independent)
            List<SmokeTestResult> results = List.of(
                    SmokeTestResult.fail("failed", "error", null)
            );

            SmokeTestReport report = new SmokeTestReport(true, results);

            // Success is explicitly set, not derived
            assertThat(report.success()).isTrue();
        }

        @Test
        @DisplayName("success false should not be affected by result statuses")
        void successFalseShouldNotBeAffectedByResultStatuses() {
            // Report says success=false even though all results passed
            List<SmokeTestResult> results = List.of(
                    SmokeTestResult.ok("passed")
            );

            SmokeTestReport report = new SmokeTestReport(false, results);

            assertThat(report.success()).isFalse();
        }
    }

    @Nested
    @DisplayName("of (derived success)")
    class DerivedSuccess {

        @Test
        @DisplayName("of should be successful when every result is ok")
        void ofSuccessfulWhenAllOk() {
            SmokeTestReport report = SmokeTestReport.of(List.of(
                    SmokeTestResult.ok("a"),
                    SmokeTestResult.ok("b")
            ));

            assertThat(report.success()).isTrue();
        }

        @Test
        @DisplayName("of should fail when any result failed")
        void ofFailsWhenAnyFailed() {
            SmokeTestReport report = SmokeTestReport.of(List.of(
                    SmokeTestResult.ok("a"),
                    SmokeTestResult.fail("b", "nope", null)
            ));

            assertThat(report.success()).isFalse();
        }

        @Test
        @DisplayName("of should be successful for an empty result list")
        void ofSuccessfulWhenEmpty() {
            SmokeTestReport report = SmokeTestReport.of(List.of());

            assertThat(report.success()).isTrue();
            assertThat(report.results()).isEmpty();
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle single result")
        void shouldHandleSingleResult() {
            SmokeTestReport report = new SmokeTestReport(true, List.of(
                    SmokeTestResult.ok("only")
            ));

            assertThat(report.results()).hasSize(1);
            assertThat(report.results().get(0).name()).isEqualTo("only");
        }

        @Test
        @DisplayName("should handle many results")
        void shouldHandleManyResults() {
            List<SmokeTestResult> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(SmokeTestResult.ok("test" + i));
            }

            SmokeTestReport report = new SmokeTestReport(true, results);

            assertThat(report.results()).hasSize(100);
        }

        @Test
        @DisplayName("should handle mixed ok and fail results")
        void shouldHandleMixedOkAndFailResults() {
            List<SmokeTestResult> results = List.of(
                    SmokeTestResult.ok("pass1"),
                    SmokeTestResult.fail("fail1", "error", null),
                    SmokeTestResult.ok("pass2"),
                    SmokeTestResult.fail("fail2", "error", new RuntimeException())
            );

            SmokeTestReport report = new SmokeTestReport(false, results);

            assertThat(report.results()).hasSize(4);
            assertThat(report.results().stream().filter(SmokeTestResult::isOk).count()).isEqualTo(2);
            assertThat(report.results().stream().filter(r -> !r.isOk()).count()).isEqualTo(2);
        }
    }
}
