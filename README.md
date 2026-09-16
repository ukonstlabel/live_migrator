# Live Migrator

Replace every instance of an old class version with a new one — and rewrite every reference to them — in a **running** JVM, without restarting the process.

Live Migrator discovers all instances of a source class on the heap, builds a replacement for each via a user-supplied migrator, then rewrites every reference in the application (fields, arrays, collections, maps, registries, records) to point at the new objects. Heap discovery uses a small native JVMTI agent; reference patching is pure reflection.

Use it for:

- **Zero-downtime evolution** — change an in-memory data model while the app keeps serving requests
- **Representation upgrades** — migrate live state to a new class shape
- **Runtime hot-fixes** — swap a buggy class implementation for a corrected one

---

## Table of contents

- [Live Migrator](#live-migrator)
  - [Table of contents](#table-of-contents)
  - [How it works](#how-it-works)
  - [Architecture](#architecture)
  - [Project structure](#project-structure)
  - [Quick start](#quick-start)
    - [Prerequisites](#prerequisites)
    - [Demo — local](#demo--local)
    - [Demo — Docker](#demo--docker)
  - [Core concepts](#core-concepts)
    - [`ClassMigrator`](#classmigrator)
    - [`MigrationEngine`](#migrationengine)
    - [Attaching to a running JVM](#attaching-to-a-running-jvm)
  - [Annotations](#annotations)
    - [`@Migrator`](#migrator)
    - [`@PhaseListener`](#phaselistener)
    - [`@CommitComponent` / `@RollbackComponent`](#commitcomponent--rollbackcomponent)
    - [`@SmokeTestComponent`](#smoketestcomponent)
    - [`@UpdateRegistry`](#updateregistry)
  - [Generic container updates](#generic-container-updates)
  - [Configuration](#configuration)
  - [Timeouts](#timeouts)
  - [Metrics \& monitoring](#metrics--monitoring)
    - [Metrics](#metrics)
    - [State \& history](#state--history)
    - [Alert logging](#alert-logging)
  - [Performance \& guarantees](#performance--guarantees)
  - [Creating a migration payload](#creating-a-migration-payload)
  - [JVM requirements](#jvm-requirements)
  - [Building \& testing](#building--testing)
    - [Test tiers](#test-tiers)
  - [API reference](#api-reference)
    - [`MigrationEngine`](#migrationengine-1)
    - [`MigrationConfig`](#migrationconfig)
    - [`MigrationConfigLoader`](#migrationconfigloader)
    - [`MigrationMetrics`](#migrationmetrics)
    - [`MigrationState` / `MigrationHistoryEntry`](#migrationstate--migrationhistoryentry)
    - [`RegistryUpdater`](#registryupdater)
    - [`MigratorDescriptor`](#migratordescriptor)
    - [`AgentLoader`](#agentloader)
    - [Enums](#enums)

---

## How it works

A migration runs as an ordered sequence of phases (each tracked by `MigrationMetrics.Phase`):

1. **First pass — allocate & migrate.** For each migrator, snapshot all live instances of its source class (via the JVMTI agent), invoke the migrator to build a replacement for each, and record the `old → new` mapping in a forwarding table.
2. **Critical phase.** The phase listener is signalled to quiesce the application, then:
   - **Second pass** — walk the heap and rewrite every reference to a migrated object.
   - **Registry update** — patch `@UpdateRegistry` fields and generic containers (`List<T>`, `Map<K,V>`, `Set<T>`, arrays, …).
   - The phase listener is signalled to resume.
3. **Smoke test.** Run smoke tests / health checks against the new objects; on failure, roll back.
4. **Commit.** Finalize (delete the checkpoint) and advance the native epoch.

The engine only *signals* the application to pause/resume — it never pauses threads itself. Coordinating quiescence is the phase listener's job. If anything fails before commit, the engine triggers a rollback; the commit/rollback decision is made exactly once even when an overall timeout races the migration to completion.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                            Running JVM                              │
│  ┌──────────────┐     ┌────────────────────────────────────────┐    │
│  │ service-demo │     │            migration-payload           │    │
│  │              │     │  ┌───────────────┐  ┌───────────────┐  │    │
│  │  OldUser ────┼─────┼──► UserMigrator  │  │   NewUser     │  │    │
│  │  instances   │     │  │  @Migrator    │  │   instances   │  │    │
│  │              │     │  └───────────────┘  └───────────────┘  │    │
│  └──────────────┘     └────────────────────────────────────────┘    │
│         ▲                          │                                │
│         │                          ▼                                │
│  ┌──────┴───────────────────────────────────────────────────────┐   │
│  │                    MigrationEngine (migrator)                │   │
│  │  1. Heap walk (JVMTI)   →  find all OldUser instances        │   │
│  │  2. Migrate             →  create a NewUser for each OldUser │   │
│  │  3. Patch references    →  replace every OldUser → NewUser   │   │
│  │  4. Update registries   →  caches, maps, collections, arrays │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         ▲
         │ Java Attach API
┌────────┴─────────────┐
│ VirtualMachineAgent  │
│ Loader (run-*.sh)    │
└──────────────────────┘
```

---

## Project structure

```
/
├── migrator/                 # Core framework — the published library
│   └── src/main/java/migrator/
│       ├── ClassMigrator.java        # The interface you implement
│       ├── engine/                   # MigrationEngine, ComponentResolver, TimeoutExecutor, timeouts
│       ├── annotations/              # @Migrator, @CommitComponent, @RollbackComponent, …
│       ├── scanner/                  # Classpath annotation scanning
│       ├── plan/                     # MigrationPlan (ordering + cycle detection), MigratorDescriptor
│       ├── heap/                     # JVMTI heap walking (NativeHeapWalker)
│       ├── patch/                    # ForwardingTable + iterative, cycle-safe reference patcher
│       ├── registry/                 # @UpdateRegistry + generic-container updates
│       ├── phase/                    # Phase listeners + MigrationContext
│       ├── commit/                   # Commit / rollback management
│       ├── crac/                     # CRaC checkpoint/restore controller
│       ├── smoke/                    # Smoke tests & health checks
│       ├── config/                   # MigrationConfig + loader (properties/YAML)
│       ├── metrics/                  # MigrationMetrics + collector
│       ├── state/                    # Global MigrationState + history
│       ├── alert/                    # Structured logging (MigrationAlertLogger)
│       └── exceptions/               # Exception types
│
├── agent/                    # Native JVMTI agent for heap walking
│   └── agent.c
│
├── examples/                 # Demo (not part of the library)
│   ├── service-demo/         # A service holding OldUser instances
│   ├── migration-payload/    # A migration agent (UserMigrator: OldUser → NewUser)
│   ├── Dockerfile, docker-compose.yml
│   └── run-service.sh, run-migration.sh, migrate.sh, docker-migrate.sh
│
└── pom.xml                   # Parent POM
```

---

## Quick start

### Prerequisites

- JDK 21+
- Maven 3.8+
- GCC (to build the native agent)
- Docker & Docker Compose (only for the Docker demo)

### Demo — local

```bash
# 1. Build the native agent + start a service holding OldUser instances
./examples/run-service.sh

# 2. In another terminal, attach and migrate OldUser → NewUser
./examples/run-migration.sh
```

The service keeps serving throughout. After the migration, all `OldUser` instances are `NewUser`, and users created afterwards are already `NewUser`.

### Demo — Docker

```bash
docker compose -f examples/docker-compose.yml build

docker compose -f examples/docker-compose.yml up -d service
docker compose -f examples/docker-compose.yml ps           # wait until healthy

docker compose -f examples/docker-compose.yml --profile migrate up migrator

docker compose -f examples/docker-compose.yml logs service
docker compose -f examples/docker-compose.yml --profile migrate down -v
```

---

## Core concepts

### `ClassMigrator`

Defines how to convert an old instance into a new one:

```java
public interface ClassMigrator<OldT, NewT> {
    NewT migrate(OldT old) throws MigrateException;
    default void validate(NewT migrated) throws MigrateException {}   // optional post-check
}
```

Migrators should be side-effect free (no DB/network writes) and ideally idempotent. The source and target types must share a common interface — it's inferred automatically and used for type-safe container updates.

### `MigrationEngine`

The orchestrator. The simplest entry points are the static `createAndMigrate(...)` factories:

```java
// Scan the classpath for @Migrator-annotated components and migrate
MigrationEngine.createAndMigrate(Set.of(ServiceMain.class));

// With a custom classloader + JAR path (e.g. from a loaded agent)
MigrationEngine.createAndMigrate(classesToScan, classLoader, jarPath);

// With explicit generic-container updates
MigrationEngine.createAndMigrate(
    classesToScan, classLoader, jarPath,
    List.of(userCache, userRegistry),   // containers to update
    User.class);                        // common interface of their elements
```

> **Config requirement:** `createAndMigrate(...)` and `loadAndApplyConfig()` call `MigrationConfigLoader.load()`, which **requires** a `migration.properties` or `migration.yml` on the classpath (it throws `MigrationConfigException` otherwise). To run without a config file, construct the engine directly and configure it programmatically — see [Configuration](#configuration).

### Attaching to a running JVM

```java
LoaderResult result = MigrationEngine.attachAndLoad(pid, agentJarPath);

// or directly
AgentLoader loader = new VirtualMachineAgentLoader();
LoaderResult result = loader.load(pid, agentJarPath);
```

---

## Annotations

Components are discovered by scanning the classpath (and, optionally, a supplied classloader/JAR).

| Annotation | Purpose | Cardinality |
|------------|---------|-------------|
| `@Migrator` | The migrator (`ClassMigrator<OldT, NewT>`) | exactly one |
| `@PhaseListener` | Coordinates app quiescence around the critical phase | exactly one |
| `@CommitComponent` | Commit manager (`extends CommitManager`) | exactly one |
| `@RollbackComponent` | Rollback manager (`extends RollbackManager`) | exactly one |
| `@SmokeTestComponent` | Post-migration smoke test (`implements SmokeTest`) | at least one |
| `@UpdateRegistry` | Marks registry/cache fields to update | any number |

### `@Migrator`

```java
@Migrator
public class UserMigrator implements ClassMigrator<OldUser, NewUser> {
    @Override public NewUser migrate(OldUser old) {
        return new NewUser(old.id, old.name, "unknown@example.com");
    }
}
```

### `@PhaseListener`

```java
@PhaseListener
public class MyPhaseListener implements MigrationPhaseListener {
    public void onBeforeCriticalPhase(MigrationContext ctx) { /* drain & pause */ }
    public void onAfterCriticalPhase(MigrationContext ctx)  { /* resume */ }
}
```

`MigrationContext` exposes `migrationId()`, `plan()`, `startedAtNanos()`, and `elapsedNanos()`.

### `@CommitComponent` / `@RollbackComponent`

```java
@CommitComponent
public class MyCommitManager extends CommitManager {
    public MyCommitManager() { super(new MyCracController()); }
}

@RollbackComponent
public class MyRollbackManager extends RollbackManager {
    public MyRollbackManager() { super(new MyCracController()); }
}
```

> Rollback is implemented via CRaC checkpoint/restore. With the default `NoopCracController`, `restoreFromCheckpoint()` is unsupported — supply a real `CracController` if you need working rollback.

### `@SmokeTestComponent`

```java
@SmokeTestComponent
public class MySmokeTest implements SmokeTest {
    public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> migrated) {
        return SmokeTestResult.ok("my-smoke-test");
    }
}
```

Each test is isolated — a thrown exception (including an `AssertionError`) becomes a failed result rather than aborting the run. `SmokeTestResult`: `ok(name)`, `fail(name, message, error)`, `isOk()`, `name()`, `message()`, `error()`.

### `@UpdateRegistry`

Marks fields (often static) holding registries/caches to update. Works for `Map`, `Collection`, arrays, and custom container types.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `replaceKeys` | boolean | `true` | Replace migrated keys in `Map` registries |
| `replaceValues` | boolean | `true` | Replace migrated values / elements |
| `deep` | boolean | `true` | Recursively patch nested container contents |
| `reflective` | boolean | `false` | Drive custom container types via reflection |

```java
public class UserCache {
    @UpdateRegistry(replaceKeys = true, replaceValues = true)
    private static Map<Integer, User> cache = new ConcurrentHashMap<>();

    @UpdateRegistry
    private List<User> recentUsers = new ArrayList<>();

    @UpdateRegistry(deep = true, reflective = true)
    private CustomRegistry<User> customRegistry;
}
```

---

## Generic container updates

The framework updates generic containers holding migrated objects, annotated or not:

```java
class UserService {
    private List<User> users;            // updated
    private Map<String, User> cache;     // updated
    private Set<User> activeUsers;       // updated
    private User[] userArray;            // updated
    private CustomRegistry<User> reg;    // updated (via reflection)
}
```

To update containers you hold directly:

```java
// During migration
MigrationEngine.createAndMigrate(classesToScan, classLoader, jarPath,
                                 List.of(container1, container2), User.class);

// Or via RegistryUpdater
registryUpdater.updateGenericContainer(myList, User.class);
registryUpdater.updateGenericContainers(containers, User.class);
```

In-place container rebuilds are exception-safe: if re-inserting the migrated contents fails partway (for example, a sorted set whose migrated element breaks the comparator), the original contents are restored rather than left empty or half-populated.

---

## Configuration

Configure via `migration.properties` or `migration.yml` on the classpath, or programmatically. System properties (`-Dmigration.*`) override file values.

| Property | Description | Default |
|----------|-------------|---------|
| `migration.heap.walk.mode` | Heap walk strategy: `FULL` or `SPEC` | `SPEC` |
| `migration.timeout.heap.walk` | Heap-walk timeout (seconds) | `0` (disabled) |
| `migration.timeout.heap.snapshot` | Heap-snapshot timeout (seconds) | `0` (disabled) |
| `migration.timeout.critical.phase` | Critical-phase timeout (seconds) | `0` (disabled) |
| `migration.timeout.smoke.test` | Smoke-test timeout (seconds) | `0` (disabled) |
| `migration.heap.size.min` | Minimum required max-heap (MB) | `0` (disabled) |
| `migration.heap.size.max` | Maximum allowed used-heap (MB) | `0` (disabled) |
| `migration.history.size` | Migration history entries to retain | `10` |
| `migration.alert.level` | `DEBUG`, `WARNING`, or `ERROR` | `WARNING` |

**migration.properties**
```properties
migration.heap.walk.mode=SPEC
migration.timeout.heap.walk=60
migration.timeout.critical.phase=30
migration.timeout.smoke.test=10
migration.heap.size.min=512
migration.alert.level=DEBUG
migration.history.size=20
```

**migration.yml**
```yaml
migration:
  heap:
    walk: { mode: SPEC }
    size: { min: 512 }
  timeout:
    heap: { walk: 60 }
    critical: { phase: 30 }
    smoke: { test: 10 }
  alert: { level: DEBUG }
  history: { size: 20 }
```

**Programmatic**
```java
MigrationConfig config = MigrationConfig.builder()
    .heapWalkMode(HeapWalkMode.SPEC)
    .heapWalkTimeoutSeconds(60)
    .criticalPhaseTimeoutSeconds(30)
    .smokeTestTimeoutSeconds(10)
    .minHeapSizeMb(512)
    .alertLevel(AlertLevel.DEBUG)
    .historySize(20)
    .build();

MigrationEngine engine = new MigrationEngine(classLoader, jarPath);
engine.applyConfig(config);
```

---

## Timeouts

```java
MigrationConfig config = MigrationConfig.builder()
    .heapWalkTimeoutSeconds(60)      // heap traversal
    .heapSnapshotTimeoutSeconds(30)  // per-class snapshots
    .criticalPhaseTimeoutSeconds(30) // phase-listener callbacks
    .smokeTestTimeoutSeconds(10)     // smoke-test execution
    .build();

// or all at once
engine.setAllTimeoutsSeconds(60);
engine.setTimeoutConfig(MigrationTimeoutConfig.builder().allTimeoutsSeconds(60).build());
```

When a timeout is exceeded, a `MigrationTimeoutException` is thrown, rollback is triggered, and the failure is logged with the `MIGRATION_TIMEOUT` marker. A timed-out migration is never both committed and rolled back: the engine settles the outcome atomically even though the per-phase timeouts run the work on a separate thread.

---

## Metrics & monitoring

### Metrics

```java
MigrationMetrics m = MigrationEngine.getLastMetrics();

long total    = m.totalDurationMs();
long firstMs  = m.phaseDuration(Phase.FIRST_PASS);
int  migrated = m.objectsMigrated();
int  patched  = m.objectsPatched();
long delta    = m.heapDelta();

System.out.println(m.summary());
// Migration #1 in 234ms | Heap: 128.5MB / 256.0MB (max 4.0GB) (delta: 12.3MB) | CPU: 15.2% -> 8.1% (peak: 45.3%) | Objects: 1000 migrated, 5000 patched

Map<String, Object> json = m.toMap();   // null-safe even for partially-built metrics
```

### State & history

```java
MigrationState state = MigrationState.getInstance();

MigrationState.Status status = state.getStatus();    // IDLE, IN_PROGRESS, SUCCESS, FAILED
Phase phase                  = state.getCurrentPhase();
long id                      = state.getCurrentMigrationId();
String lastError             = state.getLastError();

for (MigrationHistoryEntry e : state.getHistory()) {  // most-recent-first, bounded snapshot
    System.out.printf("Migration %d: %s at %s%n", e.migrationId(), e.status(), e.timestamp());
}

Map<String, Object> stateJson = state.toMap();        // for monitoring endpoints
```

### Alert logging

Events are logged in a structured, aggregator-friendly format:

```
12:00:00.000 INFO  migration - MIGRATION_STARTED id=42
12:00:00.100 INFO  migration - PHASE_STARTED id=42 phase=FIRST_PASS
12:00:00.500 INFO  migration - PHASE_COMPLETED id=42 phase=FIRST_PASS duration_ms=400
12:00:01.000 INFO  migration - MIGRATION_COMPLETED id=42 duration_ms=1000 objects_migrated=500
```

`AlertLevel` controls verbosity: `DEBUG` (all), `WARNING` (rollback + errors), `ERROR` (errors only). Errors are always logged.

---

## Performance & guarantees

Migration cost is linear in two things: the number of live objects on the heap (the discovery walk) and the size of the migrated object graph — `V+E` references (the patch). It is **flat** in total heap *bytes*, in object size, and in graph depth/shape — all confirmed by a multi-axis benchmark (see `tests/benchmarks/`).

- **Heap discovery is O(heap).** The filtered ("SPEC") walk still visits every live object to apply its class filter, so discovery scales with the *total* live-object count, not just the migrated set. Matching objects are tagged with a single per-walk tag and resolved in one `GetObjectsWithTags` call, avoiding the O(N²) trap of querying many distinct tags; a per-walk epoch keeps each walk's tag distinct from earlier ones.
- **Filtered ("SPEC") heap walk is the default.** During the critical phase the engine reflectively patches only instances of classes that can hold references to migrated objects, instead of every object on the heap. Use `FULL` mode for an exhaustive scan.
- **Reference patching is iterative and cycle-safe.** The patcher traverses with an explicit work-stack (not recursion), so deep or large cyclic graphs — linked lists, trees, rings — are patched without `StackOverflowError`; an identity-based visited set prevents reprocessing.
- **Large objects are cheap.** Primitive bulk arrays are skipped, so migrating a few very large objects costs almost nothing. Whether payload data is copied or shared is up to your `migrate()`.
- **Peak memory is ~2× only if you copy.** Old and new objects coexist until commit, so a `migrate()` that *duplicates* state peaks at ≈2× the migrated data (measured), while one that *shares* immutable fields adds only the migration's working set (≈1.3×). Share to avoid doubling memory.

**Indicative numbers** (JDK 26, 16 cores, SPEC mode; standalone probes in `migrator/src/test/java/migrator/benchmark/`, rigorous JMH multi-axis study in `tests/benchmarks/`):

| Workload | Result |
|----------|--------|
| 500,000 small objects (List + Map) | full migration ~3.2 s |
| 1,000,000 nodes across cyclic graphs | 100% patched, ~5.6 s, no stack overflow |
| 25 × 100 MB objects (2.5 GB) | migrated in ~7 ms (primitive bulk skipped, arrays shared) |

---

## Creating a migration payload

1. **New class version**
   ```java
   public class NewUser implements User {
       public final int id;
       public final String name;
       public final String email;   // new field
       public NewUser(int id, String name, String email) { /* ... */ }
   }
   ```

2. **Migrator**
   ```java
   @Migrator
   public class UserMigrator implements ClassMigrator<OldUser, NewUser> {
       @Override public NewUser migrate(OldUser old) {
           return new NewUser(old.id, old.name, "unknown@example.com");
       }
   }
   ```

3. **Agent entry point**
   ```java
   public class MigrationAgent {
       public static void agentmain(String agentArgs, Instrumentation inst) {
           ClassLoader cl = MigrationAgent.class.getClassLoader();
           MigrationEngine.createAndMigrate(Set.of(ServiceMain.class), cl, agentArgs);
       }
   }
   ```

4. **Agent JAR manifest (pom.xml)**
   ```xml
   <plugin>
     <groupId>org.apache.maven.plugins</groupId>
     <artifactId>maven-jar-plugin</artifactId>
     <configuration>
       <archive><manifestEntries>
         <Agent-Class>migration.MigrationAgent</Agent-Class>
         <Can-Redefine-Classes>true</Can-Redefine-Classes>
         <Can-Retransform-Classes>true</Can-Retransform-Classes>
       </manifestEntries></archive>
     </configuration>
   </plugin>
   ```

5. **Trigger against a running PID**
   ```bash
   java --add-modules jdk.attach \
        -cp migration-payload.jar \
        migrator.load.VirtualMachineAgentLoader <PID> migration-payload.jar
   ```

---

## JVM requirements

The **target** JVM must run with the native agent loaded (it provides the JVMTI heap-walk and its JNI bindings):

```bash
java -agentpath:/path/to/libagent.so \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     -jar your-application.jar
```

The `--add-opens` flags let the reflective patcher access fields in JDK-adjacent types. Add `-XX:+EnableDynamicAgentLoading` to suppress the JDK 21+ dynamic-agent-loading warning when attaching.

---

## Building & testing

```bash
# Library only
mvn clean install

# Everything, including examples
mvn clean install -Pexamples

# Unit tests
mvn test

# Native agent
gcc -fPIC -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/linux" \
    -shared -O2 -o agent/libagent.so agent/agent.c

# Docker images
docker compose -f examples/docker-compose.yml build
```

### Test tiers

The suite has three tiers, all driven by `mvn test`:

1. **Pure-Java unit tests** — the bulk of the suite. Engine orchestration, plan ordering/cycle detection, reference patching (including cycles, sorted/concurrent containers, and exception-safe rebuilds), registry updates, config, metrics, and the `MigrationState` machine. No native agent required.
2. **Native heap-walk tests** (`migrator/heap/NativeHeapWalkerTest`). These exercise the real JNI/JVMTI methods. Because the surefire JVM is not started with `-agentpath`, the test **self-attaches** the agent at runtime (it builds `libagent.so` from source when a toolchain is present, otherwise uses the committed `agent/libagent.so`). This needs `-Djdk.attach.allowAttachSelf=true`, which the module's surefire config already sets. If the agent can't be loaded (no toolchain, attach disabled, unsupported platform), these tests **skip** rather than fail, so `mvn test` stays green everywhere.
3. **Benchmarks** (`migrator/src/test/java/migrator/benchmark/`) — standalone `main` programs (not JUnit), run manually with the agent on `-agentpath`:

   ```bash
   mvn -q -pl migrator dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt

   java -Xmx5g -agentpath:"$PWD/agent/libagent.so" -XX:+EnableDynamicAgentLoading \
        -cp "migrator/target/classes:migrator/target/test-classes:$(cat /tmp/cp.txt)" \
        migrator.benchmark.HeapStressTest   # or CyclicGraphBench / LargeObjectBench / WalkPatchProbe
   ```

   | Benchmark | Exercises |
   |-----------|-----------|
   | `HeapStressTest` | object-count scaling (1K → 500K) |
   | `CyclicGraphBench` | deep/wide/many cyclic graphs (cycle safety, no stack overflow) |
   | `LargeObjectBench` | a few very large objects (size-independence, no copy) |
   | `WalkPatchProbe` | isolates native heap-walk time vs. Java patch time |

---

## API reference

### `MigrationEngine`

| Method | Description |
|--------|-------------|
| `createAndMigrate(classesToScan)` | Create an engine and run a migration |
| `createAndMigrate(classesToScan, classLoader, jarPath)` | …with a custom loader |
| `createAndMigrate(classesToScan, classLoader, jarPath, genericContainers, interfaceType)` | …with container updates |
| `attachAndLoad(pid, agentJarPath[, agentArgs])` | Attach to a JVM and load the agent |
| `applyConfig(config)` / `loadAndApplyConfig()` | Apply / load+apply configuration |
| `setTimeoutConfig(config)` / `setAllTimeoutsSeconds(s)` | Configure timeouts |
| `setFullHeapWalk(boolean)` / `isFullHeapWalk()` | Toggle/query FULL vs SPEC heap walk |
| `migrate(classesToScan, containers, interfaceType)` | Run a migration |
| `migrateWithTimeout(classesToScan, containers, interfaceType, timeout)` | Run with an overall timeout |
| `getLastMetrics()` | Metrics from the last migration |
| `validateHeapSize(config)` | Validate the heap against config limits |

### `MigrationConfig`

Getters: `heapWalkMode()`, `isFullHeapWalk()`, `heapWalkTimeout()`, `heapSnapshotTimeout()`, `criticalPhaseTimeout()`, `smokeTestTimeout()`, `minHeapSizeMb()`, `maxHeapSizeMb()`, `historySize()`, `alertLevel()`. Build via `MigrationConfig.builder()`; `MigrationConfig.DEFAULTS` is the all-defaults instance (SPEC, no timeouts, WARNING, history 10).

### `MigrationConfigLoader`

| Method | Description |
|--------|-------------|
| `load()` | Load from the classpath (`migration.properties`/`migration.yml`; throws if absent) |
| `loadFromFile(path)` | Load from an external `.properties`/`.yml` file |

### `MigrationMetrics`

`migrationId()`, `totalDurationMs()`, `totalDuration()`, `phaseDuration(phase)`, `objectsMigrated()`, `objectsPatched()`, `migratorCount()`, `startTime()`, `endTime()`, `heapDelta()`, `memoryBefore()`/`memoryAfter()` (→ `MemoryMetrics`), `cpu()` (→ `CpuMetrics`), `summary()`, `toMap()`.

- **MemoryMetrics:** `heapUsed()`, `heapCommitted()`, `heapMax()`, `nonHeapUsed()`, `heapSummary()`.
- **CpuMetrics:** `before()`, `after()`, `peak()`, `processors()`, `summary()`.

### `MigrationState` / `MigrationHistoryEntry`

- **MigrationState:** `getInstance()`, `getStatus()`, `getCurrentPhase()`, `getCurrentMigrationId()`, `getLastMetrics()`, `getLastError()`, `getHistory()`, `setMaxHistorySize(n)`, `toMap()`, `reset()`.
- **MigrationHistoryEntry:** `migrationId()`, `status()`, `timestamp()`, `metrics()`, `errorMessage()`.

### `RegistryUpdater`

| Method | Description |
|--------|-------------|
| `updateAnnotatedRegistries(classes, heapObjects)` | Update `@UpdateRegistry` fields |
| `updateGenericContainer(container, interfaceType)` | Update a single container |
| `updateGenericContainers(containers, interfaceType)` | Update multiple containers |
| `updateGenericFieldsInClasses(classes, heapObjects, interfaceTypes)` | Scan & update generic fields |

### `MigratorDescriptor`

`migrator()`, `from()`, `to()`, `commonInterface()` — describes a migrator and its type mapping (used by the plan, smoke tests, and metrics). The migrator class needs a no-arg constructor of any visibility.

### `AgentLoader`

`load(pid, agentJarPath)`, `load(pid, agentJarPath, agentArgs)`.

### Enums

- **HeapWalkMode** — `FULL` (entire heap) · `SPEC` (only classes that can reference migrated objects; **default**).
- **AlertLevel** — `DEBUG` (all) · `WARNING` (warnings + errors) · `ERROR` (errors only).
- **MigrationState.Status** — `IDLE` · `IN_PROGRESS` · `SUCCESS` · `FAILED`.
- **MigrationMetrics.Phase** — `FIRST_PASS` · `CRITICAL_PHASE` · `SECOND_PASS` · `REGISTRY_UPDATE` · `SMOKE_TEST`.
