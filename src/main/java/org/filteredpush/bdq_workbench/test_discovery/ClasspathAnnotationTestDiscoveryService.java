package org.filteredpush.bdq_workbench.test_discovery;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reflective implementation discovery based on ffdq-style annotations. */
public class ClasspathAnnotationTestDiscoveryService implements TestDiscoveryService {
	
	private static final Logger LOG = LoggerFactory.getLogger(ClasspathAnnotationTestDiscoveryService.class);
	
    private final List<String> scanPackages;

    public ClasspathAnnotationTestDiscoveryService(List<String> scanPackages) {
        this.scanPackages = List.copyOf(scanPackages);
    }

    @Override
    public List<DiscoveredImplementation> discover() {
        List<DiscoveredImplementation> discovered = new ArrayList<>();
        LOG.debug("Scanning packages for test implementations: {}", scanPackages);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        LOG.debug("Using class loader: {}", cl);
        LOG.debug(System.getProperty("java.class.path"));
        LOG.debug("Classpath entries: {}", String.join(", ", System.getProperty("java.class.path").split(System.getProperty("path.separator"))));
        try (var scanResult = new ClassGraph().overrideClassLoaders(cl).ignoreClassVisibility().ignoreMethodVisibility().enableAllInfo().acceptPackages(scanPackages.toArray(String[]::new)).scan()) {
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                Class<?> clazz = classInfo.loadClass();
                LOG.debug("Scanning class: {}", clazz.getName());
                Object target = null;
                for (MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {
                    Method method = methodInfo.loadClassAndGetMethod();
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
                    discovered.add(new DiscoveredImplementation(
                            providedId,
                            readProvidesVersion(method.getAnnotations()),
                            inferPhase(method.getAnnotations()),
                            clazz.getName(),
                            method.getName(),
                            readNamedValueMap(method.getAnnotations()),
                            Modifier.isStatic(method.getModifiers()) ? null : target,
                            method));
                }
            }
        } catch (Exception e) {
            throw new AppException("Failed to discover test implementations", e);
        }
        return discovered;
    }

    private static String readProvides(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if ("Provides".equals(annotation.annotationType().getSimpleName())) {
                try {
                    Method valueMethod = annotation.annotationType().getMethod("value");
                    Object value = valueMethod.invoke(annotation);
                    return value == null ? null : value.toString();
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String readProvidesVersion(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if ("ProvidesVersion".equals(annotation.annotationType().getSimpleName())) {
                try {
                    Method valueMethod = annotation.annotationType().getMethod("value");
                    Object value = valueMethod.invoke(annotation);
                    return value == null ? null : value.toString();
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Phase inferPhase(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName();
            if ("Amendment".equals(name)) {
                return Phase.AMENDMENT;
            }
            if ("Validation".equals(name) || "Issue".equals(name) || "Measure".equals(name)) {
                return Phase.PRE_AMENDMENT;
            }
        }
        return Phase.POST_AMENDMENT;
    }

    private static Map<String, String> readNamedValueMap(Annotation[] annotations) {
        Map<String, String> values = new HashMap<>();
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName();
            if ("ActedUpon".equals(name) || "Consulted".equals(name) || "Specification".equals(name)) {
                values.put(name, annotation.toString());
            }
        }
        return values;
    }
}
