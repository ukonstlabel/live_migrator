package migrator.commit;

import migrator.crac.CracController;
import migrator.exceptions.MigrateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CommitManager}.
 */
@DisplayName("CommitManager")
class CommitManagerTest {

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
            assertThatThrownBy(() -> new CommitManager(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should accept null listener")
        void shouldAcceptNullListener() {
            CommitManager manager = new CommitManager(cracController, null);

            assertThat(manager).isNotNull();
        }

        @Test
        @DisplayName("should create with CracController only")
        void shouldCreateWithCracControllerOnly() {
            CommitManager manager = new CommitManager(cracController);

            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("should delete checkpoint")
        void shouldDeleteCheckpoint() throws MigrateException {
            CommitManager manager = new CommitManager(cracController);

            manager.commit();

            verify(cracController).deleteCheckpoint();
        }

        @Test
        @DisplayName("should invoke listener on commit")
        void shouldInvokeListenerOnCommit() throws MigrateException {
            AtomicBoolean listenerCalled = new AtomicBoolean(false);
            CommitManager.CommitListener listener = () -> listenerCalled.set(true);

            CommitManager manager = new CommitManager(cracController, listener);
            manager.commit();

            assertThat(listenerCalled).isTrue();
        }

        @Test
        @DisplayName("should invoke listener after checkpoint deletion")
        void shouldInvokeListenerAfterCheckpointDeletion() throws MigrateException {
            StringBuilder order = new StringBuilder();

            doAnswer(inv -> {
                order.append("delete");
                return null;
            }).when(cracController).deleteCheckpoint();

            CommitManager.CommitListener listener = () -> order.append("-listener");

            CommitManager manager = new CommitManager(cracController, listener);
            manager.commit();

            assertThat(order.toString()).isEqualTo("delete-listener");
        }

        @Test
        @DisplayName("should not fail if listener throws")
        void shouldNotFailIfListenerThrows() throws MigrateException {
            CommitManager.CommitListener listener = () -> {
                throw new RuntimeException("Listener error");
            };

            CommitManager manager = new CommitManager(cracController, listener);

            // Should not throw - listener exception is logged but swallowed
            manager.commit();

            verify(cracController).deleteCheckpoint();
        }

        @Test
        @DisplayName("should propagate MigrateException from deleteCheckpoint")
        void shouldPropagateMigrateExceptionFromDeleteCheckpoint() throws MigrateException {
            doThrow(new MigrateException("Delete failed")).when(cracController).deleteCheckpoint();

            CommitManager manager = new CommitManager(cracController);

            assertThatThrownBy(manager::commit)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Delete failed");
        }

        @Test
        @DisplayName("should not invoke listener if deleteCheckpoint throws")
        void shouldNotInvokeListenerIfDeleteCheckpointThrows() throws MigrateException {
            doThrow(new MigrateException("Failed")).when(cracController).deleteCheckpoint();

            AtomicBoolean listenerCalled = new AtomicBoolean(false);
            CommitManager.CommitListener listener = () -> listenerCalled.set(true);

            CommitManager manager = new CommitManager(cracController, listener);

            try {
                manager.commit();
            } catch (MigrateException ignored) {}

            assertThat(listenerCalled).isFalse();
        }

        @Test
        @DisplayName("should work without listener")
        void shouldWorkWithoutListener() throws MigrateException {
            CommitManager manager = new CommitManager(cracController);

            manager.commit();

            verify(cracController).deleteCheckpoint();
        }
    }

    @Nested
    @DisplayName("CommitListener interface")
    class CommitListenerInterface {

        @Test
        @DisplayName("should be a functional interface")
        void shouldBeAFunctionalInterface() {
            // This test verifies CommitListener can be used as a lambda
            CommitManager.CommitListener listener = () -> System.out.println("committed");

            assertThat(listener).isNotNull();
        }
    }
}
