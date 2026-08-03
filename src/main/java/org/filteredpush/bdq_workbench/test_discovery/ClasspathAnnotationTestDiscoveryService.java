/** ClasspathAnnotationTestDiscoveryService.java
 *
 * Discovers BDQ test implementation methods by scanning the classpath for ffdq-style annotations.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.test_discovery;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reflective implementation discovery based on ffdq-style annotations.
 *
 * <p>Uses <a href="https://github.com/classgraph/classgraph">ClassGraph</a> to scan a configured
 * set of Java packages ({@link #scanPackages}) for classes containing methods annotated with an
 * ffdq-style {@code @Provides} annotation (the identifier of the BDQ test the method implements).
 * For each such method, the class is instantiated (if the method is not static) and a
 * {@link DiscoveredImplementation} is built recording the test identifier, inferred
 * {@link TestType} and {@link Phase}, the method's {@code @Specification} text, and metadata for
 * each of the method's parameters, so that a {@link TestBindingService} can later match resolved
 * policy tests to these implementations.
 *
 * <p>Because different deployment environments expose the annotated classes through different
 * class loaders, {@link #discover()} retries scanning with progressively broader class loader
 * strategies — the current thread's context class loader, this class's own loader, an explicit
 * scan of the {@code java.class.path} system property, and finally ClassGraph's default
 * classpath — stopping as soon as one strategy yields at least one discovered implementation.
 */
public class ClasspathAnnotationTestDiscoveryService implements TestDiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathAnnotationTestDiscoveryService.class);

    private final List<String> scanPackages;

    /**
     * Creates a discovery service that scans only the given packages.
     *
     * @param scanPackages the Java package names to scan for annotated test implementations
     */
    public ClasspathAnnotationTestDiscoveryService(List<String> scanPackages) {
        this.scanPackages = List.copyOf(scanPackages);
    }

    /**
     * Scans {@link #scanPackages} for methods annotated with {@code @Provides}, retrying with
     * increasingly broad class loader strategies until one succeeds or all have been exhausted.
     *
     * @return the discovered implementation methods; empty if none could be found under any
     *     class loader strategy
     */
    @Override
    public List<DiscoveredImplementation> discover() {
        LOG.debug("Scanning packages for test implementations: {}", scanPackages);
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader discoveryClassLoader = getClass().getClassLoader();
        String javaClassPath = System.getProperty("java.class.path", "");
        LOG.debug("Using class loader: {}", contextClassLoader);
        LOG.debug(javaClassPath);
        LOG.debug("Classpath entries: {}", String.join(", ", splitClasspathEntries(javaClassPath)));

        List<DiscoveredImplementation> discovered = discoverWith("context class loader", graph -> {
            if (contextClassLoader != null) {
                return graph.overrideClassLoaders(contextClassLoader);
            }
            return graph;
        });
        if (discovered.isEmpty() && discoveryClassLoader != null && discoveryClassLoader != contextClassLoader) {
            LOG.debug(
                    "No test implementations found via context class loader {}; retrying discovery class loader {}",
                    contextClassLoader,
                    discoveryClassLoader);
            discovered = discoverWith("discovery class loader", graph -> graph.overrideClassLoaders(discoveryClassLoader));
        }
        if (discovered.isEmpty() && !javaClassPath.isBlank()) {
            LOG.debug("No test implementations found via class loaders; retrying explicit java.class.path scan");
            discovered = discoverWith("explicit java.class.path", graph -> graph.overrideClasspath(splitClasspathEntries(javaClassPath)));
        }
        if (discovered.isEmpty() && contextClassLoader != null) {
            LOG.debug("No test implementations found via configured class loaders; retrying default classpath scan");
            discovered = discoverWith("default classpath", graph -> graph);
        }
        LOG.debug("Discovered {} implementation methods for scan packages {}", discovered.size(), scanPackages);
        return discovered;
    }

    /**
     * Runs a single ClassGraph scan of {@link #scanPackages} using the class loader
     * configuration applied by {@code configurator}, converting every method carrying an
     * ffdq-style {@code @Provides} annotation into a {@link DiscoveredImplementation}.
     *
     * <p>Classes or methods that fail to load (e.g. due to missing dependencies) are skipped
     * and logged rather than aborting the scan. For each qualifying non-static method, the
     * declaring class is instantiated once (via its no-arg constructor) and reused as the
     * {@code target} for all of that class's discovered methods.
     *
     * @param strategy a human-readable label for the class loader strategy, used only for
     *     diagnostic logging
     * @param configurator applies the desired class loader/classpath configuration to a new
     *     {@link ClassGraph}
     * @return the implementations discovered under this strategy
     * @throws AppException if the underlying classpath scan fails
     */
    private List<DiscoveredImplementation> discoverWith(String strategy, GraphConfigurator configurator) {
        List<DiscoveredImplementation> discovered = new ArrayList<>();
        ClassGraph classGraph = configurator.configure(new ClassGraph()
                .ignoreClassVisibility()
                .ignoreMethodVisibility()
                .enableAllInfo()
                .acceptPackages(scanPackages.toArray(String[]::new)));
        int scannedClassCount = 0;
        int skippedClassCount = 0;
        int skippedMethodCount = 0;
        try (var scanResult = classGraph.scan()) {
            LOG.debug("{} scan returned {} candidate classes", strategy, scanResult.getAllClasses().size());
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                scannedClassCount++;
                LOG.debug("Scanning class: {}", classInfo.getName());
                Class<?> clazz;
                try {
                    clazz = classInfo.loadClass();
                } catch (LinkageError | RuntimeException e) {
                    skippedClassCount++;
                    LOG.debug("Skipping unloadable class during discovery: {}", classInfo.getName(), e);
                    continue;
                }
                Object target = null;
                for (MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {
                    Method method;
                    try {
                        method = methodInfo.loadClassAndGetMethod();
                    } catch (LinkageError | RuntimeException e) {
                        skippedMethodCount++;
                        LOG.debug("Skipping unloadable method during discovery: {}#{}", classInfo.getName(), methodInfo.getName(), e);
                        continue;
                    }
                    String providedId = readProvides(method.getAnnotations());
                    if (providedId == null) {
                        continue;
                    }
                    if (!Modifier.isStatic(method.getModifiers()) && target == null) {
                        try {
                            target = clazz.getDeclaredConstructor().newInstance();
                        } catch (ReflectiveOperationException ignored) {
                            continue;
                        }
                    }
                    TestType testType = inferTestType(method.getAnnotations());
                    discovered.add(new DiscoveredImplementation(
                            providedId,
                            readProvidesVersion(method.getAnnotations()),
                            testType,
                            inferPhase(testType),
                            clazz.getName(),
                            method.getName(),
                            readSpecification(method.getAnnotations()),
                            readMethodParameters(method),
                            Modifier.isStatic(method.getModifiers()) ? null : target,
                            method));
                }
            }
        } catch (Exception e) {
            throw new AppException("Failed to discover test implementations", e);
        }
        LOG.debug(
                "{} discovered {} implementation methods across {} candidate classes ({} unloadable classes, {} unloadable methods)",
                strategy,
                discovered.size(),
                scannedClassCount,
                skippedClassCount,
                skippedMethodCount);
        return discovered;
    }

    /**
     * Splits a {@code java.class.path}-style string into its individual entries, dropping blank
     * entries.
     *
     * @param javaClassPath the raw classpath string, using the platform path separator
     * @return the non-blank classpath entries, or an empty array if {@code javaClassPath} is
     *     null or blank
     */
    private static String[] splitClasspathEntries(String javaClassPath) {
        if (javaClassPath == null || javaClassPath.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(javaClassPath.split(System.getProperty("path.separator")))
                .filter(entry -> entry != null && !entry.isBlank())
                .toArray(String[]::new);
    }

    /**
     * Reads the test identifier declared by an ffdq-style {@code @Provides} annotation, if the
     * method carries one.
     *
     * @param annotations the method's declared annotations
     * @return the {@code @Provides} value, or {@code null} if the method has no such annotation
     */
    private static String readProvides(Annotation[] annotations) {
        return readAnnotationValue(annotations, "Provides", "value");
    }

    /**
     * Reads the version-qualified test identifier declared by an ffdq-style
     * {@code @ProvidesVersion} annotation, if the method carries one.
     *
     * @param annotations the method's declared annotations
     * @return the {@code @ProvidesVersion} value, or {@code null} if the method has no such
     *     annotation
     */
    private static String readProvidesVersion(Annotation[] annotations) {
        return readAnnotationValue(annotations, "ProvidesVersion", "value");
    }

    /**
     * Reads the human-readable specification text declared by an ffdq-style
     * {@code @Specification} annotation, if the method carries one.
     *
     * @param annotations the method's declared annotations
     * @return the {@code @Specification} value, or {@code null} if the method has no such
     *     annotation
     */
    private static String readSpecification(Annotation[] annotations) {
        return readAnnotationValue(annotations, "Specification", "value");
    }

    /**
     * Finds the first annotation in {@code annotations} whose simple type name matches
     * {@code annotationName} and reflectively invokes its no-arg {@code methodName} accessor.
     *
     * <p>Annotations are matched by simple name (rather than by class reference) so that this
     * discovery service does not need a compile-time dependency on the ffdq annotation types.
     *
     * @param annotations the annotations to search
     * @param annotationName the simple name of the annotation type to match
     * @param methodName the name of the annotation's accessor method to invoke
     * @return the accessor's return value as a string, or {@code null} if no matching
     *     annotation is present or the accessor cannot be invoked
     */
    private static String readAnnotationValue(Annotation[] annotations, String annotationName, String methodName) {
        for (Annotation annotation : annotations) {
            if (annotationName.equals(annotation.annotationType().getSimpleName())) {
                try {
                    Method valueMethod = annotation.annotationType().getMethod(methodName);
                    Object value = valueMethod.invoke(annotation);
                    return value == null ? null : value.toString();
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Infers a method's {@link TestType} from its declared annotations.
     *
     * @param annotations the method's declared annotations
     * @return {@link TestType#VALIDATION}, {@link TestType#ISSUE}, {@link TestType#MEASURE}, or
     *     {@link TestType#AMENDMENT} if a corresponding annotation is present; otherwise
     *     {@link TestType#UNKNOWN}
     */
    private static TestType inferTestType(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName();
            if ("Validation".equals(name)) {
                return TestType.VALIDATION;
            }
            if ("Issue".equals(name)) {
                return TestType.ISSUE;
            }
            if ("Measure".equals(name)) {
                return TestType.MEASURE;
            }
            if ("Amendment".equals(name)) {
                return TestType.AMENDMENT;
            }
        }
        return TestType.UNKNOWN;
    }

    /**
     * Infers the execution {@link Phase} for a method from its {@link TestType}.
     *
     * @param testType the previously inferred test type
     * @return {@link Phase#AMENDMENT} if {@code testType} is {@link TestType#AMENDMENT};
     *     otherwise {@link Phase#PRE_AMENDMENT}
     */
    private static Phase inferPhase(TestType testType) {
        return testType == TestType.AMENDMENT ? Phase.AMENDMENT : Phase.PRE_AMENDMENT;
    }

    /**
     * Builds {@link MethodParameter} metadata for every parameter of a discovered method, in
     * declaration order.
     *
     * <p>Each parameter is classified, in priority order, by an ffdq-style parameter annotation
     * ({@link #readAnnotatedParameter}), then by the legacy two-argument
     * {@code (record, parameters)} convention ({@link #readLegacyParameter}), and finally
     * falls back to treating it as an unannotated required {@link ParameterRole#PARAMETER}.
     *
     * @param method the reflected implementation method
     * @return metadata for each of the method's parameters, in parameter order
     */
    private static List<MethodParameter> readMethodParameters(Method method) {
        List<MethodParameter> parameters = new ArrayList<>();
        Parameter[] declared = method.getParameters();
        for (int i = 0; i < declared.length; i++) {
            Parameter parameter = declared[i];
            MethodParameter metadata = readAnnotatedParameter(i, parameter);
            if (metadata == null) {
                metadata = readLegacyParameter(i, parameter, declared.length);
            }
            if (metadata == null) {
                metadata = new MethodParameter(
                        i,
                        parameter.getName(),
                        ParameterRole.PARAMETER,
                        parameter.getName(),
                        parameter.getType().getName(),
                        true);
            }
            parameters.add(metadata);
        }
        return List.copyOf(parameters);
    }

    /**
     * Classifies a parameter using its ffdq-style annotation, if present ({@code @ActedUpon},
     * {@code @Consulted}, or {@code @Parameter}).
     *
     * @param index the parameter's zero-based position in the method's declared parameter list
     * @param parameter the reflected parameter
     * @return the corresponding {@link MethodParameter}, or {@code null} if the parameter
     *     carries none of the recognized annotations
     */
    private static MethodParameter readAnnotatedParameter(int index, Parameter parameter) {
        for (Annotation annotation : parameter.getAnnotations()) {
            String simpleName = annotation.annotationType().getSimpleName();
            if ("ActedUpon".equals(simpleName)) {
                return new MethodParameter(
                        index,
                        parameter.getName(),
                        ParameterRole.ACTED_UPON,
                        readAnnotationProperty(annotation, "value"),
                        parameter.getType().getName(),
                        true);
            }
            if ("Consulted".equals(simpleName)) {
                return new MethodParameter(
                        index,
                        parameter.getName(),
                        ParameterRole.CONSULTED,
                        readAnnotationProperty(annotation, "value"),
                        parameter.getType().getName(),
                        true);
            }
            if ("Parameter".equals(simpleName)) {
                return new MethodParameter(
                        index,
                        parameter.getName(),
                        ParameterRole.PARAMETER,
                        readAnnotationProperty(annotation, "name"),
                        parameter.getType().getName(),
                        parameter.getType().isPrimitive());
            }
        }
        return null;
    }

    /**
     * Classifies a parameter using the legacy two-argument implementation convention, in which
     * an unannotated method takes a {@code Map} record representation as its first argument and
     * (optionally) a {@code Map} of parameters as its second.
     *
     * @param index the parameter's zero-based position in the method's declared parameter list
     * @param parameter the reflected parameter
     * @param parameterCount the total number of parameters declared by the method
     * @return a {@link MethodParameter} with role {@link ParameterRole#LEGACY_RECORD} or
     *     {@link ParameterRole#LEGACY_PARAMETERS} if the parameter matches the legacy
     *     convention; otherwise {@code null}
     */
    private static MethodParameter readLegacyParameter(int index, Parameter parameter, int parameterCount) {
        if (!java.util.Map.class.isAssignableFrom(parameter.getType())) {
            return null;
        }
        if (parameterCount >= 1 && index == 0) {
            return new MethodParameter(
                    index,
                    parameter.getName(),
                    ParameterRole.LEGACY_RECORD,
                    "record",
                    parameter.getType().getName(),
                    true);
        }
        if (parameterCount >= 2 && index == 1) {
            return new MethodParameter(
                    index,
                    parameter.getName(),
                    ParameterRole.LEGACY_PARAMETERS,
                    "parameters",
                    parameter.getType().getName(),
                    false);
        }
        return null;
    }

    /**
     * Reflectively invokes a no-arg accessor method on an annotation instance.
     *
     * @param annotation the annotation instance to read from
     * @param name the name of the accessor method to invoke
     * @return the accessor's return value as a string, or {@code null} if the value is null or
     *     the accessor cannot be invoked
     */
    private static String readAnnotationProperty(Annotation annotation, String name) {
        try {
            Object value = annotation.annotationType().getMethod(name).invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Applies a class loader/classpath configuration to a {@link ClassGraph} before it is
     * scanned, allowing {@link #discover()} to retry the same scan under different class
     * loader strategies.
     */
    @FunctionalInterface
    private interface GraphConfigurator {

        /**
         * Applies this configurator's class loader/classpath strategy to {@code graph}.
         *
         * @param graph the graph to configure
         * @return the configured graph (typically {@code graph} itself, mutated and returned)
         */
        ClassGraph configure(ClassGraph graph);
    }
}
