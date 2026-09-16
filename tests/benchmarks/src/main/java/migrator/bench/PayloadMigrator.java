package migrator.bench;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;

/**
 * Transformation logic {@link OldPayload} → {@link NewPayload}, reused by every strategy:
 * Live Migrator (S0) invokes it through the engine, while the serialize strategies
 * (S1/S2/S3) call it directly when "loading" state into the new version. This keeps the
 * state transformation identical for all of them, so the comparison is fair.
 */
@Migrator
public final class PayloadMigrator implements ClassMigrator<OldPayload, NewPayload> {

    @Override
    public NewPayload migrate(OldPayload old) {
        return transform(old);
    }

    /** Pure transformation without the engine — for the baseline strategies. */
    public static NewPayload transform(OldPayload old) {
        return new NewPayload(old.id, old.name, old.data, System.nanoTime());
    }
}
