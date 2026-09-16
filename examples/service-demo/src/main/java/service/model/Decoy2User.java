package service.model;

/**
 * Probe-only third implementation of {@link User}, used together with {@link DecoyUser} to pre-warm the hot call sites in
 * {@code ServiceMain.handleGetUsers} to a polymorphic state <em>before</em> a migration runs.
 *
 * <p>Rationale: with only {@code OldUser} loaded, C2 sees a unique concrete implementor of
 * {@code User} and compiles {@code user.getId()} / {@code user.getName()} as inlined field
 * loads guarded by a class-hierarchy-analysis (CHA) assumption. Loading a further implementor
 * (which is exactly what a migration payload does) invalidates that assumption and deoptimizes
 * the compiled method. Seeding decoys during warmup kills the CHA assumption up front, so a
 * later migration cannot trigger that deopt — which is how we attribute the post-migration
 * latency dip to JIT re-profiling rather than to reference patching.
 *
 * <p>Enabled only via {@code -Dservice.prewarm=mega}; unused otherwise.
 *
 * @see DecoyUser
 */
public class Decoy2User implements User {
    public final int id;
    public final String name;

    public Decoy2User(int id, String name) { this.id = id; this.name = name; }

    @Override public int getId() { return id; }
    @Override public String getName() { return name; }
    @Override public String toString() { return "Decoy2User{id=" + id + "}"; }
}
