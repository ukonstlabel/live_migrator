package migrator.bench;

import java.io.Serializable;

/**
 * The "old" version of the payload object (migration source).
 *
 * <p>{@link Serializable} is only needed by the baseline strategies S1/S2/S3
 * (serialize+restart / rolling / blue-green), which transfer state via serialization. Live
 * Migrator (S0) does not use serialization — it migrates objects directly in the heap.
 */
public final class OldPayload implements Payload, Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final String name;
    public final byte[] data;

    public OldPayload(int id, String name, byte[] data) {
        this.id = id;
        this.name = name;
        this.data = data;
    }

    @Override public int getId() { return id; }

    @Override public String getName() { return name; }

    @Override public byte[] getData() { return data; }
}
