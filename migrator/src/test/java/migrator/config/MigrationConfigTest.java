package migrator.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MigrationConfig}.
 */
class MigrationConfigTest {

    @Test
    void defaults() {
        MigrationConfig c = MigrationConfig.DEFAULTS;

        assertEquals(HeapWalkMode.SPEC, c.heapWalkMode());
        assertFalse(c.isFullHeapWalk());
        assertEquals(Duration.ZERO, c.heapWalkTimeout());
        assertEquals(0, c.minHeapSizeMb());
        assertEquals(0, c.maxHeapSizeMb());
        assertEquals(10, c.historySize());
        assertEquals(AlertLevel.WARNING, c.alertLevel());
    }

    @Test
    void builderSetsValues() {
        MigrationConfig c = MigrationConfig.builder()
                .heapWalkMode(HeapWalkMode.SPEC)
                .heapWalkTimeoutSeconds(60)
                .heapSnapshotTimeoutSeconds(30)
                .criticalPhaseTimeoutSeconds(20)
                .smokeTestTimeoutSeconds(10)
                .minHeapSizeMb(512)
                .maxHeapSizeMb(4096)
                .historySize(25)
                .alertLevel(AlertLevel.DEBUG)
                .build();

        assertEquals(HeapWalkMode.SPEC, c.heapWalkMode());
        assertFalse(c.isFullHeapWalk());
        assertEquals(Duration.ofSeconds(60), c.heapWalkTimeout());
        assertEquals(Duration.ofSeconds(30), c.heapSnapshotTimeout());
        assertEquals(Duration.ofSeconds(20), c.criticalPhaseTimeout());
        assertEquals(Duration.ofSeconds(10), c.smokeTestTimeout());
        assertEquals(512, c.minHeapSizeMb());
        assertEquals(4096, c.maxHeapSizeMb());
        assertEquals(25, c.historySize());
        assertEquals(AlertLevel.DEBUG, c.alertLevel());
    }

    @Test
    void allTimeoutsSeconds() {
        MigrationConfig c = MigrationConfig.builder()
                .allTimeoutsSeconds(45)
                .build();

        assertEquals(Duration.ofSeconds(45), c.heapWalkTimeout());
        assertEquals(Duration.ofSeconds(45), c.heapSnapshotTimeout());
        assertEquals(Duration.ofSeconds(45), c.criticalPhaseTimeout());
        assertEquals(Duration.ofSeconds(45), c.smokeTestTimeout());
    }

    @Test
    void historySizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () ->
                MigrationConfig.builder().historySize(0));
        assertThrows(IllegalArgumentException.class, () ->
                MigrationConfig.builder().historySize(-1));
    }
}
