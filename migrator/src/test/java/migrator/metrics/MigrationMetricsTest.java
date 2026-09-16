package migrator.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link MigrationMetrics} value semantics, focused on borderline construction.
 */
@DisplayName("MigrationMetrics")
class MigrationMetricsTest {

    @Test
    @DisplayName("toMap() of a minimally-built metric does not NPE on null timestamps")
    void toMapNullSafeOnIncompleteMetric() {
        // The builder leaves startTime/endTime null when unset. toMap() must serialize them as null
        // rather than throwing a NullPointerException (the engine always sets them, but the builder
        // and record constructor are public API).
        MigrationMetrics metrics = MigrationMetrics.builder()
                .migrationId(42)
                .objectsMigrated(3)
                .build();

        assertThatCode(metrics::toMap).doesNotThrowAnyException();

        Map<String, Object> serialized = metrics.toMap();
        assertThat(serialized.get("migrationId")).isEqualTo(42L);
        assertThat(serialized.get("startTime")).isNull();
        assertThat(serialized.get("endTime")).isNull();
        assertThat(serialized.get("objectsMigrated")).isEqualTo(3);
        // memory/cpu sub-records are populated by the builder, so these stay non-null.
        assertThat(serialized).containsKey("heapUsedBefore");
    }
}
