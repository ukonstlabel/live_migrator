package migrator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MigrationConfigLoader}.
 */
class MigrationConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadFromPropertiesFile() throws IOException {
        Path f = tempDir.resolve("test.properties");
        Files.writeString(f, """
                migration.heap.walk.mode=SPEC
                migration.timeout.heap.walk=60
                migration.timeout.heap.snapshot=30
                migration.heap.size.min=256
                migration.heap.size.max=2048
                migration.history.size=25
                migration.alert.level=ERROR
                """);

        MigrationConfig c = MigrationConfigLoader.loadFromFile(f);

        assertEquals(HeapWalkMode.SPEC, c.heapWalkMode());
        assertEquals(Duration.ofSeconds(60), c.heapWalkTimeout());
        assertEquals(Duration.ofSeconds(30), c.heapSnapshotTimeout());
        assertEquals(256, c.minHeapSizeMb());
        assertEquals(2048, c.maxHeapSizeMb());
        assertEquals(25, c.historySize());
        assertEquals(AlertLevel.ERROR, c.alertLevel());
    }

    @Test
    void loadFromYamlFile() throws IOException {
        Path f = tempDir.resolve("test.yml");
        Files.writeString(f, """
                migration:
                  heap:
                    walk:
                      mode: SPEC
                    size:
                      min: 512
                      max: 4096
                  timeout:
                    heap:
                      walk: 90
                      snapshot: 45
                    critical:
                      phase: 25
                    smoke:
                      test: 15
                  history:
                    size: 30
                  alert:
                    level: DEBUG
                """);

        MigrationConfig c = MigrationConfigLoader.loadFromFile(f);

        assertEquals(HeapWalkMode.SPEC, c.heapWalkMode());
        assertEquals(Duration.ofSeconds(90), c.heapWalkTimeout());
        assertEquals(Duration.ofSeconds(45), c.heapSnapshotTimeout());
        assertEquals(Duration.ofSeconds(25), c.criticalPhaseTimeout());
        assertEquals(Duration.ofSeconds(15), c.smokeTestTimeout());
        assertEquals(512, c.minHeapSizeMb());
        assertEquals(4096, c.maxHeapSizeMb());
        assertEquals(30, c.historySize());
        assertEquals(AlertLevel.DEBUG, c.alertLevel());
    }

    @Test
    void caseInsensitiveEnums() throws IOException {
        Path f = tempDir.resolve("test.properties");
        Files.writeString(f, """
                migration.heap.walk.mode=spec
                migration.alert.level=warning
                """);

        MigrationConfig c = MigrationConfigLoader.loadFromFile(f);

        assertEquals(HeapWalkMode.SPEC, c.heapWalkMode());
        assertEquals(AlertLevel.WARNING, c.alertLevel());
    }

    @Test
    void invalidValuesUseDefaults() throws IOException {
        Path f = tempDir.resolve("test.properties");
        Files.writeString(f, """
                migration.heap.walk.mode=INVALID
                migration.alert.level=INVALID
                migration.timeout.heap.walk=not-a-number
                """);

        MigrationConfig c = MigrationConfigLoader.loadFromFile(f);

        assertEquals(HeapWalkMode.SPEC, c.heapWalkMode());
        assertEquals(AlertLevel.WARNING, c.alertLevel());
        assertEquals(Duration.ZERO, c.heapWalkTimeout());
    }

    @Test
    void nonexistentFileThrows() {
        Path f = tempDir.resolve("nonexistent.properties");
        assertThrows(IOException.class, () -> MigrationConfigLoader.loadFromFile(f));
    }
}
