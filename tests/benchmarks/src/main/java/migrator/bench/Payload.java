package migrator.bench;

/**
 * Common domain interface for the benchmark payload object. Both the "old" and the "new"
 * versions implement it, which gives {@link migrator.engine.MigrationEngine} a meaningful
 * common type to migrate against.
 */
public interface Payload {
    int getId();

    String getName();

    byte[] getData();
}
