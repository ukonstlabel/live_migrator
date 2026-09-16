package migrator.scanner;

import migrator.annotations.*;
import migrator.exceptions.AnnotationNotFoundException;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scans the classpath for migration-related annotations.
 *
 * <p>This scanner uses the Reflections library to discover classes annotated with:
 * <ul>
 *   <li>{@link Migrator} - exactly one required</li>
 *   <li>{@link PhaseListener} - exactly one required</li>
 *   <li>{@link CommitComponent} - exactly one required</li>
 *   <li>{@link RollbackComponent} - exactly one required</li>
 *   <li>{@link SmokeTestComponent} - at least one required</li>
 * </ul>
 *
 * <p>The scanner can be configured to search specific classloaders and JAR files,
 * which is useful when loading migration code dynamically.
 *
 * <h2>Usage:</h2>
 * <pre>
 * // Scan using default classloader
 * AnnotationScanResult result = AnnotationScanner.scan();
 *
 * // Scan specific classloader and JAR
 * AnnotationScanResult result = AnnotationScanner.scan(myClassLoader, "/path/to/migration.jar");
 * </pre>
 *
 * @see AnnotationScanResult
 */
public final class AnnotationScanner {

    /**
     * Scans the classpath using the default classloader.
     *
     * @return the scan result containing all discovered annotated classes
     * @throws AnnotationNotFoundException if required annotations are not found
     * @throws IllegalStateException if more than one class is annotated with a single-instance annotation
     */
    public static AnnotationScanResult scan() {
        return scan((ClassLoader) null);
    }

    /**
     * Scans the classpath using a specific classloader.
     *
     * @param classLoader the classloader to scan, or null for default
     * @return the scan result containing all discovered annotated classes
     * @throws AnnotationNotFoundException if required annotations are not found
     * @throws IllegalStateException if more than one class is annotated with a single-instance annotation
     */
    public static AnnotationScanResult scan(ClassLoader classLoader) {
        return scan(classLoader, (URL) null);
    }

    /**
     * Scans the classpath using a specific classloader and JAR path.
     *
     * @param classLoader the classloader to scan, or null for default
     * @param jarPath path to an additional JAR file to scan, or null to skip
     * @return the scan result containing all discovered annotated classes
     * @throws AnnotationNotFoundException if required annotations are not found
     * @throws IllegalStateException if more than one class is annotated with a single-instance annotation
     * @throws RuntimeException if the JAR path is invalid or does not exist
     */
    public static AnnotationScanResult scan(ClassLoader classLoader, String jarPath) {
        URL jarUrl = null;
        if (jarPath != null) {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) {
                throw new RuntimeException("JAR path does not exist: " + jarPath);
            }
            try {
                jarUrl = jarFile.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException("Invalid JAR path: " + jarPath, e);
            }
        }
        return scan(classLoader, jarUrl);
    }

    /**
     * Scans the classpath using a specific classloader and additional URL.
     *
     * @param classLoader the classloader to scan, or null for default
     * @param additionalUrl URL of an additional location to scan, or null to skip
     * @return the scan result containing all discovered annotated classes
     * @throws AnnotationNotFoundException if required annotations are not found
     * @throws IllegalStateException if more than one class is annotated with a single-instance annotation
     */
    public static AnnotationScanResult scan(ClassLoader classLoader, URL additionalUrl) {
        ConfigurationBuilder config = new ConfigurationBuilder()
                .addScanners(Scanners.TypesAnnotated);

        // Collect URLs from various sources
        List<URL> urls = new ArrayList<>(ClasspathHelper.forJavaClassPath());

        if (classLoader != null) {
            urls.addAll(ClasspathHelper.forClassLoader(classLoader));
            config.addClassLoaders(classLoader);
        }

        if (additionalUrl != null) {
            urls.add(additionalUrl);
        }

        config.setUrls(urls);

        Reflections reflections = new Reflections(config);

        Class<?> migrator = single(reflections, Migrator.class);
        Class<?> phase = single(reflections, PhaseListener.class);
        Class<?> commit = single(reflections, CommitComponent.class);
        Class<?> rollback = single(reflections, RollbackComponent.class);

        Set<Class<?>> smokeTests =
                reflections.getTypesAnnotatedWith(SmokeTestComponent.class);

        if (smokeTests.isEmpty()) {
            throw new AnnotationNotFoundException("No @SmokeTestComponent found");
        }

        return new AnnotationScanResult(
                migrator,
                phase,
                commit,
                rollback,
                smokeTests
        );
    }

    /**
     * Returns the one class annotated with the given annotation.
     *
     * @throws AnnotationNotFoundException if no class carries the annotation
     * @throws IllegalStateException if more than one does
     */
    private static Class<?> single(
            Reflections reflections,
            Class<? extends Annotation> annotation
    ) {
        Set<Class<?>> classes = reflections.getTypesAnnotatedWith(annotation);

        if (classes.isEmpty()) {
            throw new AnnotationNotFoundException(
                    "No @" + annotation.getSimpleName() + " found"
            );
        }

        if (classes.size() > 1) {
            throw new IllegalStateException(
                    "Multiple @" + annotation.getSimpleName() + " found: " + classes
            );
        }

        return classes.iterator().next();
    }
}
