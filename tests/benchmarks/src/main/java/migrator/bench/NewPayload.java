package migrator.bench;

import java.io.Serializable;

/**
 * The "new" version of the payload object (migration target). It changes the shape of the
 * data: field {@code name → label} plus an added {@code migratedAt} — exactly the schema
 * change that HotSwap does not support out of the box.
 */
public final class NewPayload implements Payload, Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final String label;
    public final byte[] data;
    public final long migratedAt;

    public NewPayload(int id, String label, byte[] data, long migratedAt) {
        this.id = id;
        this.label = label;
        this.data = data;
        this.migratedAt = migratedAt;
    }

    @Override public int getId() { return id; }

    @Override public String getName() { return label; }

    @Override public byte[] getData() { return data; }
}
