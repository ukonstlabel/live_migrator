package migrator.commit;

import migrator.crac.CracController;
import migrator.exceptions.MigrateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RollbackManager}.
 */
@DisplayName("RollbackManager")
class RollbackManagerTest {

    @Mock
    private CracController cracController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("should require non-null CracController")
        void shouldRequireNonNullCracController() {
            assertThatThrownBy(() -> new RollbackManager(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should create with valid CracController")
        void shouldCreateWithValidCracController() {
            RollbackManager manager = new RollbackManager(cracController);

            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("should call restoreFromCheckpoint")
        void shouldCallRestoreFromCheckpoint() throws MigrateException {
            RollbackManager manager = new RollbackManager(cracController);

            try {
                manager.rollback();
            } catch (MigrateException ignored) {
                // Expected since restoreFromCheckpoint returns normally
            }

            verify(cracController).restoreFromCheckpoint();
        }

        @Test
        @DisplayName("should throw MigrateException if restoreFromCheckpoint returns normally")
        void shouldThrowMigrateExceptionIfRestoreReturnsNormally() {
            // If restoreFromCheckpoint returns normally, it means restore didn't happen
            RollbackManager manager = new RollbackManager(cracController);

            assertThatThrownBy(manager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("CRaC restore did not occur");
        }

        @Test
        @DisplayName("should rethrow MigrateException directly without wrapping")
        void shouldRethrowMigrateExceptionDirectlyWithoutWrapping() throws MigrateException {
            doThrow(new MigrateException("Restore failed")).when(cracController).restoreFromCheckpoint();

            RollbackManager manager = new RollbackManager(cracController);

            assertThatThrownBy(manager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Restore failed")
                    .hasNoCause();
        }

        @Test
        @DisplayName("should wrap RuntimeException in MigrateException")
        void shouldWrapRuntimeExceptionInMigrateException() throws MigrateException {
            doThrow(new RuntimeException("Unexpected error")).when(cracController).restoreFromCheckpoint();

            RollbackManager manager = new RollbackManager(cracController);

            assertThatThrownBy(manager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Rollback failed")
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should preserve original exception message")
        void shouldPreserveOriginalExceptionMessage() throws MigrateException {
            doThrow(new MigrateException("Specific error message")).when(cracController).restoreFromCheckpoint();

            RollbackManager manager = new RollbackManager(cracController);

            assertThatThrownBy(manager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Specific error message");
        }

        @Test
        @DisplayName("should rethrow MigrateException directly")
        void shouldRethrowMigrateExceptionDirectly() throws MigrateException {
            // This tests the special case where restoreFromCheckpoint returns normally
            // and a MigrateException is thrown
            RollbackManager manager = new RollbackManager(cracController);

            assertThatThrownBy(manager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasNoCause(); // Direct throw, not wrapped
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle Error from restoreFromCheckpoint")
        void shouldHandleErrorFromRestoreFromCheckpoint() throws MigrateException {
            doThrow(new OutOfMemoryError("OOM")).when(cracController).restoreFromCheckpoint();

            RollbackManager manager = new RollbackManager(cracController);

            // OutOfMemoryError is not caught, it should propagate
            assertThatThrownBy(manager::rollback)
                    .isInstanceOf(OutOfMemoryError.class);
        }
    }
}
