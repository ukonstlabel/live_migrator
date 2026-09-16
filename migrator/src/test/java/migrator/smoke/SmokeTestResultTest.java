package migrator.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SmokeTestResult}.
 */
@DisplayName("SmokeTestResult")
class SmokeTestResultTest {

    @Nested
    @DisplayName("ok factory method")
    class OkFactoryMethod {

        @Test
        @DisplayName("should create passing result with name")
        void shouldCreatePassingResultWithName() {
            SmokeTestResult result = SmokeTestResult.ok("test-name");

            assertThat(result.name()).isEqualTo("test-name");
            assertThat(result.isOk()).isTrue();
            assertThat(result.message()).isNull();
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("should handle null name")
        void shouldHandleNullName() {
            SmokeTestResult result = SmokeTestResult.ok(null);

            assertThat(result.name()).isNull();
            assertThat(result.isOk()).isTrue();
        }

        @Test
        @DisplayName("should handle empty name")
        void shouldHandleEmptyName() {
            SmokeTestResult result = SmokeTestResult.ok("");

            assertThat(result.name()).isEmpty();
            assertThat(result.isOk()).isTrue();
        }
    }

    @Nested
    @DisplayName("fail factory method")
    class FailFactoryMethod {

        @Test
        @DisplayName("should create failing result with all fields")
        void shouldCreateFailingResultWithAllFields() {
            RuntimeException error = new RuntimeException("test error");
            SmokeTestResult result = SmokeTestResult.fail("test-name", "failure message", error);

            assertThat(result.name()).isEqualTo("test-name");
            assertThat(result.isOk()).isFalse();
            assertThat(result.message()).isEqualTo("failure message");
            assertThat(result.error()).isSameAs(error);
        }

        @Test
        @DisplayName("should create failing result with null error")
        void shouldCreateFailingResultWithNullError() {
            SmokeTestResult result = SmokeTestResult.fail("test", "message", null);

            assertThat(result.isOk()).isFalse();
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("should create failing result with null message")
        void shouldCreateFailingResultWithNullMessage() {
            SmokeTestResult result = SmokeTestResult.fail("test", null, null);

            assertThat(result.isOk()).isFalse();
            assertThat(result.message()).isNull();
        }

        @Test
        @DisplayName("should preserve exception details")
        void shouldPreserveExceptionDetails() {
            Exception cause = new IllegalStateException("root cause");
            RuntimeException error = new RuntimeException("wrapped", cause);

            SmokeTestResult result = SmokeTestResult.fail("test", "msg", error);

            assertThat(result.error()).isSameAs(error);
            assertThat(result.error().getCause()).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("withName")
    class WithName {

        @Test
        @DisplayName("should create new result with different name")
        void shouldCreateNewResultWithDifferentName() {
            SmokeTestResult original = SmokeTestResult.ok("original");

            SmokeTestResult renamed = original.withName("new-name");

            assertThat(renamed.name()).isEqualTo("new-name");
            assertThat(renamed.isOk()).isTrue();
            assertThat(original.name()).isEqualTo("original"); // original unchanged
        }

        @Test
        @DisplayName("should preserve ok status when renaming")
        void shouldPreserveOkStatusWhenRenaming() {
            SmokeTestResult original = SmokeTestResult.ok("orig");

            SmokeTestResult renamed = original.withName("renamed");

            assertThat(renamed.isOk()).isTrue();
        }

        @Test
        @DisplayName("should preserve fail status when renaming")
        void shouldPreserveFailStatusWhenRenaming() {
            Exception error = new RuntimeException("err");
            SmokeTestResult original = SmokeTestResult.fail("orig", "msg", error);

            SmokeTestResult renamed = original.withName("renamed");

            assertThat(renamed.isOk()).isFalse();
            assertThat(renamed.message()).isEqualTo("msg");
            assertThat(renamed.error()).isSameAs(error);
        }

        @Test
        @DisplayName("should allow renaming to null")
        void shouldAllowRenamingToNull() {
            SmokeTestResult original = SmokeTestResult.ok("original");

            SmokeTestResult renamed = original.withName(null);

            assertThat(renamed.name()).isNull();
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("withName should not modify original")
        void withNameShouldNotModifyOriginal() {
            SmokeTestResult original = SmokeTestResult.ok("original");

            original.withName("changed");

            assertThat(original.name()).isEqualTo("original");
        }

        @Test
        @DisplayName("should return different instance on withName")
        void shouldReturnDifferentInstanceOnWithName() {
            SmokeTestResult original = SmokeTestResult.ok("test");

            SmokeTestResult renamed = original.withName("test2");

            assertThat(renamed).isNotSameAs(original);
        }
    }
}
