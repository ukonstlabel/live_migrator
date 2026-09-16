package migrator.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MigrateException}.
 */
@DisplayName("MigrateException")
class MigrateExceptionTest {

    static class OldUser {}
    static class NewUser {}

    @Nested
    @DisplayName("constructor with message only")
    class ConstructorWithMessageOnly {

        @Test
        @DisplayName("should store message")
        void shouldStoreMessage() {
            MigrateException ex = new MigrateException("Migration failed");

            assertThat(ex.getMessage()).isEqualTo("Migration failed");
        }

        @Test
        @DisplayName("should have null diagnostic fields")
        void shouldHaveNullDiagnosticFields() {
            MigrateException ex = new MigrateException("Error");

            assertThat(ex.getObjectIdStr()).isNull();
            assertThat(ex.getObjectIdentityHash()).isNull();
            assertThat(ex.getFrom()).isNull();
            assertThat(ex.getTo()).isNull();
            assertThat(ex.getStage()).isNull();
        }

        @Test
        @DisplayName("should have no cause")
        void shouldHaveNoCause() {
            MigrateException ex = new MigrateException("Error");

            assertThat(ex.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("constructor with message and cause")
    class ConstructorWithMessageAndCause {

        @Test
        @DisplayName("should store message and cause")
        void shouldStoreMessageAndCause() {
            RuntimeException cause = new RuntimeException("Root cause");

            MigrateException ex = new MigrateException("Migration failed", cause);

            assertThat(ex.getMessage()).isEqualTo("Migration failed");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("should handle null cause")
        void shouldHandleNullCause() {
            MigrateException ex = new MigrateException("Error", (Throwable) null);

            assertThat(ex.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("constructor with full context")
    class ConstructorWithFullContext {

        @Test
        @DisplayName("should store all diagnostic fields")
        void shouldStoreAllDiagnosticFields() {
            OldUser obj = new OldUser();

            MigrateException ex = new MigrateException(
                    "Migration failed",
                    obj,
                    OldUser.class,
                    NewUser.class,
                    "PATCHING"
            );

            assertThat(ex.getFrom()).isEqualTo(OldUser.class);
            assertThat(ex.getTo()).isEqualTo(NewUser.class);
            assertThat(ex.getStage()).isEqualTo("PATCHING");
            assertThat(ex.getObjectIdStr()).contains("OldUser");
            assertThat(ex.getObjectIdentityHash()).isEqualTo(System.identityHashCode(obj));
        }

        @Test
        @DisplayName("should handle null object")
        void shouldHandleNullObject() {
            MigrateException ex = new MigrateException(
                    "Error",
                    null,
                    OldUser.class,
                    NewUser.class,
                    "STAGE"
            );

            assertThat(ex.getObjectIdStr()).isEqualTo("null");
            assertThat(ex.getObjectIdentityHash()).isNull();
        }

        @Test
        @DisplayName("should handle null from and to classes")
        void shouldHandleNullFromAndToClasses() {
            MigrateException ex = new MigrateException(
                    "Error",
                    new Object(),
                    null,
                    null,
                    "STAGE"
            );

            assertThat(ex.getFrom()).isNull();
            assertThat(ex.getTo()).isNull();
        }
    }

    @Nested
    @DisplayName("constructor with full context and cause")
    class ConstructorWithFullContextAndCause {

        @Test
        @DisplayName("should store all fields including cause")
        void shouldStoreAllFieldsIncludingCause() {
            OldUser obj = new OldUser();
            RuntimeException cause = new RuntimeException("Root");

            MigrateException ex = new MigrateException(
                    "Migration failed",
                    obj,
                    OldUser.class,
                    NewUser.class,
                    "PATCHING",
                    cause
            );

            assertThat(ex.getMessage()).contains("Migration failed");
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getFrom()).isEqualTo(OldUser.class);
            assertThat(ex.getTo()).isEqualTo(NewUser.class);
            assertThat(ex.getStage()).isEqualTo("PATCHING");
        }
    }

    @Nested
    @DisplayName("getMessage with diagnostics")
    class GetMessageWithDiagnostics {

        @Test
        @DisplayName("should include stage in message")
        void shouldIncludeStageInMessage() {
            MigrateException ex = new MigrateException(
                    "Error",
                    null,
                    null,
                    null,
                    "ALLOCATION"
            );

            assertThat(ex.getMessage()).contains("[stage=ALLOCATION]");
        }

        @Test
        @DisplayName("should include from class in message")
        void shouldIncludeFromClassInMessage() {
            MigrateException ex = new MigrateException(
                    "Error",
                    null,
                    OldUser.class,
                    null,
                    null
            );

            assertThat(ex.getMessage()).contains("[from=");
            assertThat(ex.getMessage()).contains("OldUser");
        }

        @Test
        @DisplayName("should include to class in message")
        void shouldIncludeToClassInMessage() {
            MigrateException ex = new MigrateException(
                    "Error",
                    null,
                    null,
                    NewUser.class,
                    null
            );

            assertThat(ex.getMessage()).contains("[to=");
            assertThat(ex.getMessage()).contains("NewUser");
        }

        @Test
        @DisplayName("should include object description in message")
        void shouldIncludeObjectDescriptionInMessage() {
            OldUser obj = new OldUser();

            MigrateException ex = new MigrateException(
                    "Error",
                    obj,
                    OldUser.class,
                    NewUser.class,
                    "STAGE"
            );

            assertThat(ex.getMessage()).contains("[object=");
        }

        @Test
        @DisplayName("should not include null fields in message")
        void shouldNotIncludeNullFieldsInMessage() {
            MigrateException ex = new MigrateException("Error");

            assertThat(ex.getMessage()).doesNotContain("[stage=");
            assertThat(ex.getMessage()).doesNotContain("[from=");
            assertThat(ex.getMessage()).doesNotContain("[to=");
            assertThat(ex.getMessage()).doesNotContain("[object=");
        }
    }

    @Nested
    @DisplayName("safeDescribe")
    class SafeDescribe {

        @Test
        @DisplayName("should handle object with custom toString")
        void shouldHandleObjectWithCustomToString() {
            Object obj = new Object() {
                @Override
                public String toString() {
                    return "custom-representation";
                }
            };

            MigrateException ex = new MigrateException(
                    "Error", obj, null, null, null
            );

            assertThat(ex.getObjectIdStr()).contains("custom-representation");
        }

        @Test
        @DisplayName("should handle object with throwing toString")
        void shouldHandleObjectWithThrowingToString() {
            Object obj = new Object() {
                @Override
                public String toString() {
                    throw new RuntimeException("toString failed");
                }
            };

            MigrateException ex = new MigrateException(
                    "Error", obj, null, null, null
            );

            assertThat(ex.getObjectIdStr()).contains("toString failed");
        }

        @Test
        @DisplayName("should handle object with null toString")
        void shouldHandleObjectWithNullToString() {
            Object obj = new Object() {
                @Override
                public String toString() {
                    return null;
                }
            };

            MigrateException ex = new MigrateException(
                    "Error", obj, null, null, null
            );

            assertThat(ex.getObjectIdStr()).isNotNull();
            assertThat(ex.getObjectIdStr()).contains("@");
        }

        @Test
        @DisplayName("should include identity hash code")
        void shouldIncludeIdentityHashCode() {
            Object obj = new Object();
            int hash = System.identityHashCode(obj);

            MigrateException ex = new MigrateException(
                    "Error", obj, null, null, null
            );

            assertThat(ex.getObjectIdStr()).contains("@" + hash);
        }
    }

    @Nested
    @DisplayName("inheritance")
    class Inheritance {

        @Test
        @DisplayName("should be a checked exception")
        void shouldBeACheckedException() {
            assertThat(MigrateException.class).hasSuperclass(Exception.class);
        }

        @Test
        @DisplayName("should not be a runtime exception")
        void shouldNotBeARuntimeException() {
            assertThat(RuntimeException.class.isAssignableFrom(MigrateException.class)).isFalse();
        }
    }
}
