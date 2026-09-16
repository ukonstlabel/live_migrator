package migrator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import migrator.ClassMigrator;
import migrator.alert.MigrationAlertLogger;
import migrator.config.MigrationConfig;
import migrator.config.MigrationConfigLoader;
import migrator.commit.*;
import migrator.exceptions.MigrateException;
import migrator.exceptions.MigrationTimeoutException;
import migrator.load.*;
import migrator.heap.*;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import migrator.metrics.MigrationMetricsCollector;
import migrator.patch.*;
import migrator.phase.*;
import migrator.plan.*;
import migrator.registry.RegistryUpdater;
import migrator.scanner.*;
import migrator.smoke.SmokeTestReport;
import migrator.smoke.SmokeTestRunner;
import migrator.state.MigrationState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Final MigrationEngine — orchestrates live migration end-to-end, including:
 *  - first pass (allocate & migrate)
 *  - signal before critical phase (app should quiesce)
 *  - second pass (patch references)
 *  - registry updates
 *  - signal after critical phase (app may resume)
 *  - smoke-tests
 *  - commit (delete checkpoint) OR rollback (restore checkpoint)
 *
 * Notes:
 *  - Engine does NOT perform pause/resume itself; it only signals via MigrationPhaseListener.
 *  - RollbackManager.restore may not return (platform-specific).
 *  - Migrations must be serialized: a single global {@link MigrationState} and the static
 *    {@code lastMetrics} assume one migration runs at a time per JVM. Do not run migrations
 *    concurrently (whether on one engine instance or several).
 */
public final class MigrationEngine {

    private static final Logger log = LoggerFactory.getLogger(MigrationEngine.class);

    private final MigrationPlan plan;
    private final HeapWalker heapWalker;
    private final ForwardingTable forwarding;
    private final ReferencePatcher referencePatcher;
    private final RegistryUpdater registryUpdater;
    private final MigrationPhaseListener phaseListener;

    private final SmokeTestRunner smokeRunner;
    private final CommitManager commitManager;
    private final RollbackManager rollbackManager;

    // migration id generator
    private static final AtomicLong MIGRATION_COUNTER = new AtomicLong(1L);

    // cache of migrate method per migrator class to avoid reflection cost
    private final ConcurrentMap<Class<?>, Method> migrateMethodCache = new ConcurrentHashMap<>();

    // cache of validate method per migrator class; the interface default is a no-op and is skipped
    private final ConcurrentMap<Class<?>, Method> validateMethodCache = new ConcurrentHashMap<>();

    // Ensures rollback runs once even if the timeout thread and worker thread both react to a failure.
    private final AtomicBoolean rollbackInvoked = new AtomicBoolean(false);

    // Decides the terminal outcome of a migration exactly once. When migrateWithTimeout runs
    // doMigrate on a worker thread, both that worker and the timing-out caller can race to finalize:
    // the winner of this CAS owns the commit-or-rollback decision and the state/metrics recording,
    // so a migration is never both committed (by the worker) and rolled back (by the timeout), and
    // its terminal state is recorded only once.
    private final AtomicBoolean finalized = new AtomicBoolean(false);

    // Configuration: true = full heap walk, false = filtered heap walk for specified classes only.
    // Filtered is the default: it only tags instances of the classes that can hold references to
    // migrated objects, avoiding an O(heap) reflective scan during the critical (quiesced) phase.
    private boolean fullHeapWalk = false;

    // Metrics collection
    private final MigrationMetricsCollector metricsCollector = new MigrationMetricsCollector();
    private static volatile MigrationMetrics lastMetrics;

    // Timeout configuration for migration operations
    private MigrationTimeoutConfig timeoutConfig = MigrationTimeoutConfig.DEFAULTS;

    public MigrationEngine() throws MigrateException {
        this(null, null);
    }

    public MigrationEngine(ClassLoader classLoader) throws MigrateException {
        this(classLoader, null);
    }

    public MigrationEngine(ClassLoader classLoader, String jarPath) throws MigrateException {
        AnnotationScanResult scan = AnnotationScanner.scan(classLoader, jarPath);
        ComponentResolver resolver = new ComponentResolver();

        try {
            MigratorDescriptor descriptor = new MigratorDescriptor(resolver.resolveMigrator(scan.migrator()));
            plan = MigrationPlan.build(List.of(descriptor));
        } catch (MigrateException e) {
            throw new MigrateException("Failed to build migration plan", e);
        }

        heapWalker = new NativeHeapWalker();
        forwarding = new ForwardingTable();
        referencePatcher = new ReflectionReferencePatcher(forwarding);
        registryUpdater = new RegistryUpdater(forwarding, referencePatcher);
        smokeRunner = resolver.resolveSmokeTestRunner(scan.smokeTests());
        commitManager = resolver.resolveCommitManager(scan.commitManager());
        rollbackManager = resolver.resolveRollbackManager(scan.rollbackManager());

        MigrationPhaseListener pl = resolver.resolvePhaseListener(scan.phaseListener());
        phaseListener = pl == null ? NoopPhaseListener.INSTANCE : pl;
    }

    /**
     * Set heap walk mode.
     * @param fullHeapWalk true for full heap walk (default), false for filtered heap walk
     * @return this engine for method chaining
     */
    public MigrationEngine setFullHeapWalk(boolean fullHeapWalk) {
        this.fullHeapWalk = fullHeapWalk;
        return this;
    }

    /**
     * Apply migration configuration.
     */
    public MigrationEngine applyConfig(MigrationConfig config) {
        if (config == null) return this;

        this.fullHeapWalk = config.isFullHeapWalk();
        this.timeoutConfig = MigrationTimeoutConfig.builder()
                .heapWalkTimeout(config.heapWalkTimeout())
                .heapSnapshotTimeout(config.heapSnapshotTimeout())
                .criticalPhaseTimeout(config.criticalPhaseTimeout())
                .smokeTestTimeout(config.smokeTestTimeout())
                .build();

        MigrationState.getInstance().setMaxHistorySize(config.historySize());
        MigrationAlertLogger.setAlertLevel(config.alertLevel());

        log.debug("Applied config: {}", config);
        return this;
    }

    /**
     * Load configuration from the default classpath resource and apply it.
     *
     * @return this engine for method chaining
     * @see MigrationConfigLoader#load()
     */
    public MigrationEngine loadAndApplyConfig() {
        return applyConfig(MigrationConfigLoader.load());
    }

    /**
     * Validate heap size is within configured limits.
     */
    public static void validateHeapSize(MigrationConfig config) throws MigrateException {
        if (config == null) return;

        long MB = 1024 * 1024;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / MB;
        long maxMb = rt.maxMemory() / MB;

        if (config.minHeapSizeMb() > 0 && maxMb < config.minHeapSizeMb()) {
            throw new MigrateException(
                    "Heap " + maxMb + " MB below minimum " + config.minHeapSizeMb() + " MB");
        }

        if (config.maxHeapSizeMb() > 0 && usedMb > config.maxHeapSizeMb()) {
            throw new MigrateException(
                    "Used heap " + usedMb + " MB exceeds max " + config.maxHeapSizeMb() + " MB");
        }
    }

    /**
     * @return true if full heap walk is enabled, false for filtered heap walk
     */
    public boolean isFullHeapWalk() {
        return fullHeapWalk;
    }

    /**
     * Set timeout configuration for migration operations.
     *
     * <p>Timeouts can be configured for:
     * <ul>
     *   <li>Heap walk operations</li>
     *   <li>Heap snapshot creation</li>
     *   <li>Critical phase callbacks</li>
     *   <li>Smoke test execution</li>
     * </ul>
     *
     * <h2>Example:</h2>
     * <pre>
     * engine.setTimeoutConfig(MigrationTimeoutConfig.builder()
     *     .heapWalkTimeoutSeconds(60)
     *     .criticalPhaseTimeoutSeconds(30)
     *     .smokeTestTimeoutSeconds(10)
     *     .build());
     * </pre>
     *
     * @param config the timeout configuration, or null to use defaults (no timeouts)
     * @return this engine for method chaining
     * @see MigrationTimeoutConfig
     */
    public MigrationEngine setTimeoutConfig(MigrationTimeoutConfig config) {
        this.timeoutConfig = config != null ? config : MigrationTimeoutConfig.DEFAULTS;
        log.debug("Timeout configuration set: {}", this.timeoutConfig);
        return this;
    }

    /**
     * Get the current timeout configuration.
     *
     * @return the current timeout configuration
     */
    public MigrationTimeoutConfig getTimeoutConfig() {
        return timeoutConfig;
    }

    /**
     * Get the metrics from the last migration execution.
     *
     * @return the last migration metrics, or null if no migration has run
     */
    public static MigrationMetrics getLastMetrics() {
        return lastMetrics;
    }

    /**
     * Convenience method to set all timeouts to the same value.
     *
     * @param timeout the timeout duration for all operations
     * @return this engine for method chaining
     */
    public MigrationEngine setAllTimeouts(Duration timeout) {
        return setTimeoutConfig(MigrationTimeoutConfig.builder()
                .allTimeouts(timeout)
                .build());
    }

    /**
     * Convenience method to set all timeouts to the same value in seconds.
     *
     * @param seconds the timeout in seconds for all operations, or 0 to disable
     * @return this engine for method chaining
     */
    public MigrationEngine setAllTimeoutsSeconds(long seconds) {
        return setTimeoutConfig(MigrationTimeoutConfig.builder()
                .allTimeoutsSeconds(seconds)
                .build());
    }

    /**
     * Factory method that creates a MigrationEngine and immediately runs migration.
     *
     * <p>Configuration (heap walk mode, timeouts, etc.) is loaded from classpath
     * (migration.properties or migration.yml).
     *
     * <h2>Example:</h2>
     * <pre>
     * MigrationEngine.createAndMigrate(Set.of(MyService.class));
     * </pre>
     *
     * @param classesToScan classes to scan for registry updates
     * @return the MigrationEngine after migration completes
     * @throws MigrateException if migration fails or times out
     */
    public static MigrationEngine createAndMigrate(
            Collection<Class<?>> classesToScan) throws MigrateException {
        return createAndMigrate(classesToScan, null, null, null, null);
    }

    /**
     * Factory method that creates a MigrationEngine and immediately runs migration.
     *
     * <p>Configuration (heap walk mode, timeouts, etc.) is loaded from classpath
     * (migration.properties or migration.yml).
     *
     * <h2>Example:</h2>
     * <pre>
     * // With agent classloader
     * MigrationEngine.createAndMigrate(Set.of(MyService.class), agentClassLoader, jarPath);
     * </pre>
     *
     * @param classesToScan classes to scan for registry updates
     * @param classLoader optional classloader to scan for annotated classes (null for default)
     * @param jarPath optional path to JAR file containing migration classes (null for default)
     * @return the MigrationEngine after migration completes
     * @throws MigrateException if migration fails or times out
     */
    public static MigrationEngine createAndMigrate(
            Collection<Class<?>> classesToScan,
            ClassLoader classLoader,
            String jarPath) throws MigrateException {
        return createAndMigrate(classesToScan, classLoader, jarPath, null, null);
    }

    /**
     * Factory method that creates a MigrationEngine, runs migration, and updates generic containers.
     *
     * <p>Configuration (heap walk mode, timeouts, etc.) is loaded from classpath
     * (migration.properties or migration.yml).
     *
     * <p>This overload allows updating generic containers (like {@code List<User>}, {@code Map<String, User>})
     * where elements implement a common interface. After migration completes, the specified containers
     * are scanned and any elements matching the interface type are replaced with their migrated versions.
     *
     * <h2>Example:</h2>
     * <pre>
     * List&lt;User&gt; userCache = ...;
     * Map&lt;String, User&gt; userRegistry = ...;
     *
     * MigrationEngine.createAndMigrate(
     *     Set.of(MyService.class),
     *     null,
     *     null,
     *     List.of(userCache, userRegistry),
     *     User.class
     * );
     * </pre>
     *
     * @param classesToScan classes to scan for registry updates
     * @param classLoader optional classloader to scan for annotated classes (null for default)
     * @param jarPath optional path to JAR file containing migration classes (null for default)
     * @param genericContainers containers holding elements of the interface type to update (null if not needed)
     * @param interfaceType the common interface type of elements in the containers (null if not needed)
     * @return the MigrationEngine after migration completes
     * @throws MigrateException if migration fails or times out
     */
    public static MigrationEngine createAndMigrate(
            Collection<Class<?>> classesToScan,
            ClassLoader classLoader,
            String jarPath,
            Collection<?> genericContainers,
            Class<?> interfaceType) throws MigrateException {
        MigrationConfig config = MigrationConfigLoader.load();
        validateHeapSize(config);

        MigrationEngine engine = new MigrationEngine(classLoader, jarPath);
        engine.applyConfig(config);

        // Note: the heap-walk timeout doubles as the overall migration budget here, as there is no
        // separate total-migration-timeout setting.
        Duration timeout = config.heapWalkTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            engine.migrateWithTimeout(classesToScan, genericContainers, interfaceType, timeout);
        } else {
            engine.migrate(classesToScan, genericContainers, interfaceType);
        }
        return engine;
    }

    /**
     * Attach to a running JVM and load the migration agent.
     *
     * <p>This method uses the Java Attach API to connect to a running JVM process
     * and dynamically load the migration agent JAR. The agent's {@code agentmain}
     * method is then invoked to trigger the migration.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(String pid, String agentJarPath) throws MigrateException {
        return attachAndLoad(pid, agentJarPath, null);
    }

    /**
     * Attach to a running JVM and load the migration agent with custom arguments.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @param agentArgs additional arguments to pass to the agent
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(String pid, String agentJarPath, String agentArgs) throws MigrateException {
        if (agentArgs == null)
            return VirtualMachineAgentLoader.attachAndLoad(pid, agentJarPath);
        else
            return VirtualMachineAgentLoader.attachAndLoad(pid, agentJarPath, agentArgs);
    }

    /**
     * Attach to a running JVM and load the migration agent using a custom loader.
     *
     * @param loader the agent loader to use
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(AgentLoader loader, String pid, String agentJarPath) throws MigrateException {
        return loader.load(pid, agentJarPath);
    }

    public MigrationEngine(
            Class<? extends ClassMigrator<?, ?>> migrator,
            MigrationPhaseListener phaseListener,
            SmokeTestRunner smokeRunner,
            CommitManager commitManager,
            RollbackManager rollbackManager
    ) throws MigrateException {
        try {
            MigratorDescriptor descriptor = new MigratorDescriptor(migrator);
            this.plan = MigrationPlan.build(List.of(descriptor));
        } catch (MigrateException e) {
            throw new MigrateException("Failed to build migration plan", e);
        }

        heapWalker = new NativeHeapWalker();
        forwarding = new ForwardingTable();
        referencePatcher = new ReflectionReferencePatcher(forwarding);
        registryUpdater = new RegistryUpdater(forwarding, referencePatcher);
        this.phaseListener = phaseListener == null ? NoopPhaseListener.INSTANCE : phaseListener;
        this.smokeRunner = Objects.requireNonNull(smokeRunner, "smokeRunner");
        this.commitManager = Objects.requireNonNull(commitManager, "commitManager");
        this.rollbackManager = Objects.requireNonNull(rollbackManager, "rollbackManager");
    }

    /**
     * Run migration with generic container updates. classesToScan are typically target classes and are used by RegistryUpdater.
     */
    public void migrate(Collection<Class<?>> classesToScan, Collection<?> genericContainers, Class<?> interfaceType) throws MigrateException {
        doMigrate(classesToScan, genericContainers, interfaceType);
    }

    /**
     * Run migration with a total timeout. If the timeout is exceeded, rollback is triggered.
     *
     * @param classesToScan classes to scan for registry updates
     * @param genericContainers optional containers to update
     * @param interfaceType optional interface type for generic containers
     * @param timeout maximum duration for the entire migration
     * @throws MigrateException if migration fails or times out
     */
    public void migrateWithTimeout(
            Collection<Class<?>> classesToScan,
            Collection<?> genericContainers,
            Class<?> interfaceType,
            Duration timeout) throws MigrateException {
        Objects.requireNonNull(timeout, "timeout");

        log.info("Starting migration with timeout of {}", timeout);

        try {
            TimeoutExecutor.executeWithTimeoutChecked(
                    "migration",
                    timeout,
                    () -> doMigrate(classesToScan, genericContainers, interfaceType)
            );
        } catch (MigrationTimeoutException e) {
            // The overall migration exceeded the timeout. doMigrate runs on a worker thread, which
            // may be about to commit; claim finalization first so we never roll back a migration the
            // worker is committing. If the worker already finalized (committed or recorded failure),
            // surface the timeout without touching its outcome.
            long migrationId = MigrationState.getInstance().getCurrentMigrationId();
            if (!finalized.compareAndSet(false, true)) {
                throw new MigrateException("Migration exceeded " + timeout.toMillis()
                        + "ms but was finalized by the worker before rollback could start", e);
            }
            log.error("Migration timed out after {}, triggering rollback", timeout);
            MigrationAlertLogger.migrationTimeout(migrationId, timeout.toMillis(), MigrationState.getInstance().getCurrentPhase());
            MigrationAlertLogger.rollbackTriggered(migrationId, "timeout");
            try {
                tryRollback();
                MigrationAlertLogger.rollbackCompleted(migrationId, true);
            } catch (Exception rollbackEx) {
                MigrationAlertLogger.rollbackCompleted(migrationId, false);
                MigrationState.getInstance().migrationFailed(migrationId, e, lastMetrics);
                throw new MigrateException("Migration timed out and rollback failed: " + rollbackEx.getMessage(), e);
            }
            MigrationState.getInstance().migrationFailed(migrationId, e, lastMetrics);
            throw new MigrateException("Migration timed out after " + timeout.toMillis() + "ms", e);
        } catch (MigrateException me) {
            // doMigrate already reported the failure (and rolled back where applicable); re-throw.
            throw me;
        } catch (Exception e) {
            throw new MigrateException("Migration failed: " + e.getMessage(), e);
        }
    }

    private void doMigrate(Collection<Class<?>> classesToScan, Collection<?> genericContainers, Class<?> interfaceType) throws MigrateException {
        // Empty plan is a no-op: check it before requiring classesToScan so an empty plan never
        // throws on a null scan list it would not have used.
        if (plan.orderedMigrators().isEmpty()) return;
        Objects.requireNonNull(classesToScan, "classesToScan");

        final long migrationId = MIGRATION_COUNTER.getAndIncrement();
        rollbackInvoked.set(false);
        finalized.set(false);
        final MigrationContext ctx = new MigrationContext(plan, migrationId);
        final Set<Object> allResolvedOldObjects = new LinkedHashSet<>();
        final Map<MigratorDescriptor, List<Object>> createdPerMigrator = new LinkedHashMap<>();
        final int[] patchedCount = {0};

        // Track migration state and log start
        MigrationState.getInstance().migrationStarted(migrationId);
        MigrationAlertLogger.migrationStarted(migrationId);

        metricsCollector.start(migrationId).migratorCount(plan.orderedMigrators().size());
        // Tracks whether the app may have been quiesced by onBeforeCriticalPhase but not yet
        // resumed by onAfterCriticalPhase. A one-element array so the critical-phase lambda can
        // mutate it; the finally block uses it as a safety net to guarantee the app is resumed.
        // It is set BEFORE onBefore is invoked (so a partial quiesce that throws still gets a
        // resume) and cleared BEFORE onAfter is attempted (so a failing onAfter is not retried by
        // the finally — see onAfterCriticalPhase's at-least-once/idempotency contract).
        final boolean[] beforeCriticalCalled = {false};

        // Set once this thread wins the finalization CAS (claims the commit). It lets the catch
        // blocks know a post-commit failure is still ours to record, without re-winning the CAS.
        boolean ownsOutcome = false;

        try {
            // FIRST PASS
            MigrationState.getInstance().setCurrentPhase(Phase.FIRST_PASS);
            MigrationAlertLogger.phaseStarted(migrationId, Phase.FIRST_PASS);
            long phaseStart = System.currentTimeMillis();
            metricsCollector.timed(Phase.FIRST_PASS, () ->
                    firstPassAllocateAndMigrate(allResolvedOldObjects, createdPerMigrator));
            MigrationAlertLogger.phaseCompleted(migrationId, Phase.FIRST_PASS, System.currentTimeMillis() - phaseStart);

            // CRITICAL PHASE
            MigrationState.getInstance().setCurrentPhase(Phase.CRITICAL_PHASE);
            MigrationAlertLogger.phaseStarted(migrationId, Phase.CRITICAL_PHASE);
            long criticalPhaseStart = System.currentTimeMillis();
            metricsCollector.timed(Phase.CRITICAL_PHASE, () -> {
                // Mark "may be quiesced" before signalling, so even an onBefore that throws after a
                // partial quiesce is resumed by the finally block.
                beforeCriticalCalled[0] = true;
                signalBeforeCriticalPhase(ctx);

                // Straggler rescan under quiescence. The first-pass snapshot ran *before* the
                // application was quiesced, so new instances of a source class may have been created
                // in the snapshot-to-quiesce window — they would otherwise miss the forwarding table
                // and never be migrated. Now that onBeforeCriticalPhase has paused the application
                // (which, per the MigrationPhaseListener contract, must stop creating source-class
                // instances), re-run each migrator: processMigrator's forwarding.contains guard makes
                // this idempotent, so only the stragglers are migrated and appended.
                rescanStragglersUnderQuiescence(allResolvedOldObjects, createdPerMigrator);

                // Build the pass-2 working set AFTER the rescan so stragglers (and their new objects)
                // are patched too, and refresh the migrated-object count for metrics.
                metricsCollector.objectsMigrated(
                        createdPerMigrator.values().stream().mapToInt(List::size).sum());
                Set<Object> pass2Objects = new LinkedHashSet<>(allResolvedOldObjects);
                createdPerMigrator.values().forEach(pass2Objects::addAll);

                // Compute the set of classes that may hold references to migrated objects once,
                // then reuse it for both the filtered heap walk and static-field patching.
                Set<Class<?>> classesToPatch = collectClassesToPatch(classesToScan, pass2Objects);

                // SECOND PASS
                metricsCollector.timed(Phase.SECOND_PASS, () ->
                        patchedCount[0] = secondPassPatchReferencesWithCount(pass2Objects, classesToPatch));

                safeAutoPatchStaticFields(classesToPatch);

                // REGISTRY UPDATE
                metricsCollector.timed(Phase.REGISTRY_UPDATE, () -> {
                    registryUpdater.updateAnnotatedRegistries(classesToScan, pass2Objects);
                    updateGenericContainersIfPresent(genericContainers, interfaceType);
                    updateGenericFields(classesToScan, pass2Objects, interfaceType);
                });

                // Clear before attempting onAfter: it runs exactly once here on the normal path; if
                // it throws, the finally must not invoke it again.
                beforeCriticalCalled[0] = false;
                signalAfterCriticalPhase(ctx);
            });
            MigrationAlertLogger.phaseCompleted(migrationId, Phase.CRITICAL_PHASE, System.currentTimeMillis() - criticalPhaseStart);

            metricsCollector.objectsPatched(patchedCount[0]);

            // SMOKE TESTS
            MigrationState.getInstance().setCurrentPhase(Phase.SMOKE_TEST);
            MigrationAlertLogger.phaseStarted(migrationId, Phase.SMOKE_TEST);
            long smokeTestStart = System.currentTimeMillis();
            SmokeTestReport report = metricsCollector.timed(Phase.SMOKE_TEST,
                    () -> runSmokeTestsWithTimeout(createdPerMigrator));
            MigrationAlertLogger.phaseCompleted(migrationId, Phase.SMOKE_TEST, System.currentTimeMillis() - smokeTestStart);

            if (!report.success()) {
                log.error("Smoke tests failed for migration id={}", migrationId);
                MigrationAlertLogger.rollbackTriggered(migrationId, "smoke test failure");
                try {
                    tryRollback();
                    MigrationAlertLogger.rollbackCompleted(migrationId, true);
                } catch (Exception rbEx) {
                    MigrationAlertLogger.rollbackCompleted(migrationId, false);
                    throw new MigrateException("Smoke tests failed and rollback failed: " + rbEx.getMessage(), rbEx);
                }
                // Failure state, alert and metrics are recorded exactly once by the central
                // catch(MigrateException) below — don't report them here too (that produced a
                // duplicate history entry and a duplicate alert).
                throw new MigrateException("Smoke tests failed for migration id=" + migrationId);
            }

            // Claim the terminal outcome before the irreversible commit. If a concurrently
            // timing-out caller already finalized (and rolled back) this migration, abort here
            // rather than deleting the checkpoint — never commit a migration that was rolled back.
            if (!finalized.compareAndSet(false, true)) {
                throw new MigrateException(
                        "Migration was finalized (timed out and rolled back) before commit; aborting");
            }
            ownsOutcome = true;
            commitWithRollback();
            migratorAdvanceEpoch();

            lastMetrics = metricsCollector.finish();
            log.info("Migration metrics: {}", lastMetrics.summary());

            // Track successful completion
            MigrationState.getInstance().setCurrentPhase(null);
            MigrationState.getInstance().migrationCompleted(migrationId, lastMetrics);
            MigrationAlertLogger.migrationCompleted(migrationId, lastMetrics);

        } catch (MigrateException me) {
            // Record the failure if we own the outcome — either we already claimed it (a post-commit
            // failure) or we win the CAS now. If the timeout path already finalized (and recorded /
            // rolled back), don't double-record or fight it; just propagate.
            if (ownsOutcome || finalized.compareAndSet(false, true)) {
                finishMetricsOnError();
                MigrationState.getInstance().migrationFailed(migrationId, me, lastMetrics);
                MigrationAlertLogger.migrationFailed(migrationId, me, MigrationState.getInstance().getCurrentPhase(), lastMetrics);
            }
            throw me;
        } catch (Exception e) {
            if (ownsOutcome || finalized.compareAndSet(false, true)) {
                finishMetricsOnError();
                MigrationState.getInstance().migrationFailed(migrationId, e, lastMetrics);
                MigrationAlertLogger.migrationFailed(migrationId, e, MigrationState.getInstance().getCurrentPhase(), lastMetrics);
            }
            cleanupAndRollback(allResolvedOldObjects, e);
        } finally {
            if (beforeCriticalCalled[0]) {
                safeAfterCriticalPhase(ctx, migrationId);
            }
            // Reset per-migration state so a reused engine starts each run with a clean table and
            // doesn't pin the previous run's old objects (or leak stale mappings into the next run,
            // which the MigrateException failure path would otherwise leave behind).
            forwarding.clear();
        }
    }

    /** Updates caller-supplied generic containers when both the containers and interface type are present. */
    private void updateGenericContainersIfPresent(Collection<?> containers, Class<?> interfaceType) {
        if (containers != null && interfaceType != null) {
            log.debug("Updating {} generic containers", containers.size());
            registryUpdater.updateGenericContainers(containers, interfaceType);
        }
    }

    /** Updates generic fields in the scanned classes for the migrated types (plus any explicit interface type). */
    private void updateGenericFields(Collection<Class<?>> classesToScan, Set<Object> pass2Objects, Class<?> interfaceType) {
        Set<Class<?>> types = extractMigratedInterfaceTypes();
        if (interfaceType != null) types.add(interfaceType);
        if (!types.isEmpty()) {
            registryUpdater.updateGenericFieldsInClasses(classesToScan, pass2Objects, types);
        }
    }

    /** Finalizes metrics on the failure path and logs the partial summary. */
    private void finishMetricsOnError() {
        lastMetrics = metricsCollector.finish();
        log.warn("Migration failed - metrics: {}", lastMetrics.summary());
    }

    /** Invokes the after-critical-phase callback from a finally block, swallowing (logging) any exception. */
    private void safeAfterCriticalPhase(MigrationContext ctx, long migrationId) {
        try {
            phaseListener.onAfterCriticalPhase(ctx);
        } catch (Exception e) {
            log.warn("onAfterCriticalPhase threw during finally (migration id={})", migrationId, e);
        }
    }

    /* ---------------- private helper methods ---------------- */

    /**
     * Extracts interface types from the migration plan.
     *
     * <p>This method collects all "from" types being migrated, as well as any common
     * interfaces they implement. These are used to update generic fields that hold
     * elements of these types.
     *
     * @return set of interface types to look for in generic fields
     */
    private Set<Class<?>> extractMigratedInterfaceTypes() {
        Set<Class<?>> interfaceTypes = new LinkedHashSet<>();

        for (MigratorDescriptor desc : plan.orderedMigrators()) {
            Class<?> fromClass = desc.from();
            Class<?> toClass = desc.to();

            if (fromClass != null) {
                // Add the from class itself
                interfaceTypes.add(fromClass);

                // Add all interfaces implemented by the from class
                for (Class<?> iface : fromClass.getInterfaces()) {
                    interfaceTypes.add(iface);
                }

                // Add superclass if it's not Object
                Class<?> superclass = fromClass.getSuperclass();
                if (superclass != null && superclass != Object.class) {
                    interfaceTypes.add(superclass);
                }
            }

            if (toClass != null) {
                // Add interfaces from the to class as well (they might share common interfaces)
                for (Class<?> iface : toClass.getInterfaces()) {
                    interfaceTypes.add(iface);
                }
            }
        }

        log.debug("Extracted {} migrated interface types from migration plan", interfaceTypes.size());
        return interfaceTypes;
    }

    /** First pass: runs each migrator in plan order to allocate new objects and populate the forwarding table. */
    private void firstPassAllocateAndMigrate(
        Set<Object> allResolvedOldObjects,
        Map<MigratorDescriptor, List<Object>> createdPerMigrator
    ) throws MigrateException {
        for (MigratorDescriptor desc : plan.orderedMigrators()) {
            processMigrator(desc, allResolvedOldObjects, createdPerMigrator);
        }
    }

    /**
     * Re-runs the migrators under quiescence to catch source-class instances created after the
     * first-pass snapshot but before the application was paused. Because {@link #processMigrator}
     * skips objects already in the forwarding table, this is idempotent for already-migrated objects
     * and migrates only the stragglers, appending them to {@code createdPerMigrator}.
     */
    private void rescanStragglersUnderQuiescence(
        Set<Object> allResolvedOldObjects,
        Map<MigratorDescriptor, List<Object>> createdPerMigrator
    ) throws MigrateException {
        int before = createdPerMigrator.values().stream().mapToInt(List::size).sum();
        firstPassAllocateAndMigrate(allResolvedOldObjects, createdPerMigrator);
        int caught = createdPerMigrator.values().stream().mapToInt(List::size).sum() - before;
        if (caught > 0) {
            log.info("Straggler rescan under quiescence migrated {} object(s) created during the first pass", caught);
        }
    }

    /** Signals the phase listener to quiesce before the critical phase, under the critical-phase timeout. */
    private void signalBeforeCriticalPhase(MigrationContext ctx) throws MigrateException {
        try {
            TimeoutExecutor.executeWithTimeoutChecked(
                    "onBeforeCriticalPhase",
                    timeoutConfig.criticalPhaseTimeout(),
                    () -> phaseListener.onBeforeCriticalPhase(ctx)
            );
        } catch (MigrateException me) {
            throw me;
        } catch (Exception e) {
            throw new MigrateException("Application refused to enter critical phase: " + e.getMessage(), e);
        }
    }


    /**
     * Snapshots all live instances of one migrator's source class and migrates each, recording the
     * old&rarr;new mapping in the forwarding table. Objects already migrated are skipped.
     */
    private void processMigrator(
            MigratorDescriptor desc,
            Set<Object> allResolvedOldObjects,
            Map<MigratorDescriptor, List<Object>> createdPerMigrator
    ) throws MigrateException {
        Class<?> from = desc.from();
        Object migrator = desc.migrator();

        Object[] objects = TimeoutExecutor.executeWithTimeout(
                "heapSnapshot(" + from.getSimpleName() + ")",
                timeoutConfig.heapSnapshotTimeout(),
                () -> heapWalker.snapshotObjects(from)
        );
        if (objects == null || objects.length == 0) return;

        // computeIfAbsent (not put): the straggler rescan re-runs this method under quiescence, and
        // must append newly-migrated objects to the list from the first pass rather than replace it.
        List<Object> createdForThisMigrator = createdPerMigrator.computeIfAbsent(desc, d -> new ArrayList<>());

        for (Object oldObj : objects) {
            if (oldObj == null || forwarding.contains(oldObj)) {
                if (oldObj != null) allResolvedOldObjects.add(oldObj);
                continue;
            }

            Object newObj = invokeMigrate(migrator, oldObj);
            if (newObj == null) {
                throw new MigrateException("Migrator returned null for " + oldObj.getClass().getName());
            }

            invokeValidate(migrator, newObj);

            forwarding.put(oldObj, newObj);
            allResolvedOldObjects.add(oldObj);
            createdForThisMigrator.add(newObj);
        }
    }

    /**
     * Second pass: walks the heap (full or filtered by {@code classesToPatch}) and patches every
     * reachable object's references to migrated objects. Falls back to patching only the known
     * pass-2 objects if the heap walk fails.
     *
     * @return the number of objects patched
     */
    private int secondPassPatchReferencesWithCount(Set<Object> pass2Objects, Set<Class<?>> classesToPatch) {
        Set<Object> objectsToPatch = null;
        int patchedCount = 0;

        try {
            if (fullHeapWalk) {
                // Full heap walk - patch all objects on the heap
                log.debug("Using full heap walk");
                objectsToPatch = TimeoutExecutor.executeWithTimeoutChecked(
                        "heapWalkFull",
                        timeoutConfig.heapWalkTimeout(),
                        () -> heapWalker.walkHeap()
                );
            } else {
                // Filtered heap walk - only walk objects of specified classes
                log.debug("Using filtered heap walk for {} classes", classesToPatch.size());
                objectsToPatch = TimeoutExecutor.executeWithTimeoutChecked(
                        "heapWalkFiltered",
                        timeoutConfig.heapWalkTimeout(),
                        () -> heapWalker.walkHeap(classesToPatch)
                );
            }

            if (objectsToPatch != null && !objectsToPatch.isEmpty()) {
                // Patch all objects from the heap walk in one batch (shared visited set, so a
                // connected migrated graph is traversed once — see patchObjects).
                referencePatcher.patchObjects(objectsToPatch);
                return objectsToPatch.size();
            }
        } catch (Exception e) {
            // Heap walk failed or timed out: fall back to patching the known pass-2 objects. Note
            // the fallback itself is unbounded (no timeout), so surface this at warn level.
            log.warn("heapWalker failed: {}, falling back to pass2Objects (unbounded)", e.toString());
        }

        // Fallback: patch only pass2Objects
        for (Object obj : pass2Objects) {
            referencePatcher.patchObject(obj);
            patchedCount++;
        }
        return patchedCount;
    }

    /** Patches static fields of every candidate class, isolating (logging) any failure. */
    private void safeAutoPatchStaticFields(Set<Class<?>> classesToPatch) {
        try {
            classesToPatch.forEach(this::safePatchStaticFields);
        } catch (Exception e) {
            log.warn("autoPatchStaticFields failed: {}", e.toString());
        }
    }

    /** Signals the phase listener to resume after the critical phase; on failure attempts rollback and rethrows. */
    private void signalAfterCriticalPhase(MigrationContext ctx) throws MigrateException {
        try {
            TimeoutExecutor.executeWithTimeoutChecked(
                    "onAfterCriticalPhase",
                    timeoutConfig.criticalPhaseTimeout(),
                    () -> phaseListener.onAfterCriticalPhase(ctx)
            );
        } catch (MigrateException me) {
            try {
                tryRollback();
            } catch (Exception rbEx) {
                throw new MigrateException("onAfterCriticalPhase failed: " + me.getMessage()
                        + " and rollback failed: " + rbEx.getMessage(), rbEx);
            }
            throw me;
        } catch (Exception e) {
            try {
                tryRollback();
            } catch (Exception rbEx) {
                throw new MigrateException("onAfterCriticalPhase failed: " + e.getMessage()
                        + " and rollback failed: " + rbEx.getMessage(), rbEx);
            }
            throw new MigrateException("PhaseListener.onAfterCriticalPhase failed: " + e.getMessage(), e);
        }
    }

    /** Runs all smoke tests under the configured smoke-test timeout. */
    private SmokeTestReport runSmokeTestsWithTimeout(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
        return TimeoutExecutor.executeWithTimeout(
                "smokeTests",
                timeoutConfig.smokeTestTimeout(),
                () -> smokeRunner.runAll(createdPerMigrator)
        );
    }

    /** Commits the migration; if commit fails, attempts rollback and reports both outcomes. */
    private void commitWithRollback() throws MigrateException {
        try {
            commitManager.commit();
        } catch (Exception commitEx) {
            try {
                tryRollback();
            } catch (Exception rbEx) {
                throw new MigrateException("Commit failed: " + commitEx.getMessage()
                        + ", rollback also failed: " + rbEx.getMessage(), rbEx);
            }
            throw new MigrateException("Commit failed: " + commitEx.getMessage(), commitEx);
        }
    }

    /**
     * Collects the set of classes (and their superclass chains) that may hold references to migrated
     * objects: the scanned classes plus the concrete classes of all pass-2 objects. Used both to filter
     * the heap walk and to drive static-field patching.
     */
    private Set<Class<?>> collectClassesToPatch(Collection<Class<?>> classesToScan, Collection<Object> pass2Objects) {
        Set<Class<?>> classesToPatch = new LinkedHashSet<>();

        if (classesToScan != null) {
            for (Class<?> cls : classesToScan) {
                addClassHierarchy(cls, classesToPatch);
            }
        }

        if (pass2Objects != null) {
            for (Object o : pass2Objects) {
                if (o == null) continue;
                // Many objects share a class; the early-exit in addClassHierarchy makes
                // each distinct class (and its supers) cost only one pass.
                addClassHierarchy(o.getClass(), classesToPatch);
            }
        }

        return classesToPatch;
    }

    /**
     * Adds {@code cls} and its non-Object superclasses to {@code out}. If {@code cls} is already
     * present, returns immediately — its superclass chain was added by a prior call.
     */
    private void addClassHierarchy(Class<?> cls, Set<Class<?>> out) {
        while (cls != null && cls != Object.class) {
            if (!out.add(cls)) return;
            cls = cls.getSuperclass();
        }
    }

    /** Patches one class's static fields, isolating (logging) any failure. */
    private void safePatchStaticFields(Class<?> cls) {
        try {
            referencePatcher.patchStaticFields(cls);
        } catch (Exception e) {
            log.warn("Failed to patch static fields for class {} : {}", cls != null ? cls.getName() : "null", e.toString());
        }
    }


    /** Best-effort removal of every old&rarr;new mapping from the forwarding table after a failure. */
    private void cleanupForwarding(Set<Object> allResolvedOldObjects) {
        int removed = 0;
        for (Object oldObj : allResolvedOldObjects) {
            try {
                forwarding.remove(oldObj);
                removed++;
            } catch (Exception ignore) {
                // best-effort
            }
        }
        if (removed > 0) {
            log.warn("Cleanup removed {} forwarding entries after unexpected error", removed);
        }
    }

    /** Invokes the migrator's {@code migrate} method on {@code oldObj}, caching the resolved method per migrator class. */
    private Object invokeMigrate(Object migrator, Object oldObj) throws MigrateException {
        try {
            Method m = migrateMethodCache.get(migrator.getClass());
            if (m == null) {
                m = findMigrateMethod(migrator.getClass());
                m.setAccessible(true);
                migrateMethodCache.putIfAbsent(migrator.getClass(), m);
            }
            return m.invoke(migrator, oldObj);
        } catch (MigrateException me) {
            throw me;
        } catch (Exception ex) {
            throw new MigrateException("Failed to invoke migrate on " + migrator.getClass().getName(), ex);
        }
    }

    /** Finds the single-arg {@code migrate} method on a migrator class, preferring public then declared. */
    private Method findMigrateMethod(Class<?> cls) throws MigrateException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals("migrate") && m.getParameterCount() == 1) {
                return m;
            }
        }

        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals("migrate") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return m;
            }
        }

        throw new MigrateException("Cannot find migrate(OldT) method on migrator " + cls.getName());
    }

    /**
     * Invokes the migrator's {@code validate(NewT)} hook on a freshly migrated object, if the migrator
     * overrides it. The {@link ClassMigrator#validate} default is a no-op and is skipped.
     */
    private void invokeValidate(Object migrator, Object newObj) throws MigrateException {
        Class<?> cls = migrator.getClass();
        Method m = validateMethodCache.computeIfAbsent(cls, this::findValidateMethod);
        if (m == null || m.isDefault()) {
            return; // not overridden — nothing to validate
        }
        try {
            m.invoke(migrator, newObj);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof MigrateException me) throw me;
            throw new MigrateException("Validation failed for migrated " + cls.getName(), cause);
        } catch (Exception ex) {
            throw new MigrateException("Failed to invoke validate on " + cls.getName(), ex);
        }
    }

    /**
     * Finds the single-arg {@code validate} method, preferring a concrete override over the
     * synthetic bridge or the interface default. Returns the default itself when not overridden.
     */
    private Method findValidateMethod(Class<?> cls) {
        Method fallback = null;
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals("validate") || m.getParameterCount() != 1) continue;
            if (!m.isDefault() && !m.isBridge()) {
                m.setAccessible(true);
                return m; // concrete override
            }
            if (fallback == null) fallback = m;
        }
        return fallback;
    }


    /**
     * Best-effort delegation to NativeHeapWalker.advanceEpoch() if available. Runs after commit,
     * so any failure is logged rather than propagated.
     */
    private void migratorAdvanceEpoch() {
        try {
            NativeHeapWalker.advanceEpoch();
        } catch (Throwable t) {
            // Best-effort native bookkeeping that runs after commit; a failure here — including a
            // native UnsatisfiedLinkError (an Error) when the agent library isn't loaded — must not
            // fail an already-committed migration (the checkpoint is gone, rollback is impossible).
            log.warn("Failed to advance native epoch (migration already committed): {}", t.toString());
        }
    }

    /** Invokes the rollback manager at most once per migration; later callers are no-ops. */
    private void tryRollback() throws Exception {
        if (rollbackInvoked.compareAndSet(false, true)) {
            rollbackManager.rollback();
        }
    }

    /** Cleans up the forwarding table and triggers rollback after an unexpected failure, reporting both outcomes. */
    private void cleanupAndRollback(Set<Object> allResolvedOldObjects, Exception original) throws MigrateException {
        long migrationId = MigrationState.getInstance().getCurrentMigrationId();
        MigrationAlertLogger.rollbackTriggered(migrationId, original.getMessage());
        try {
            cleanupForwarding(allResolvedOldObjects);
            tryRollback();
            MigrationAlertLogger.rollbackCompleted(migrationId, true);
        } catch (Exception rbEx) {
            MigrationAlertLogger.rollbackCompleted(migrationId, false);
            throw new MigrateException("Migration failed: " + original.getMessage()
                    + " ; attempted rollback failed: " + rbEx.getMessage(), original);
        }
        // Rollback succeeded, but the migration still failed: surface the original
        // failure to the caller instead of returning normally (which would falsely
        // report success for a migration that was reverted).
        throw new MigrateException("Migration failed and was rolled back: " + original.getMessage(), original);
    }
}
