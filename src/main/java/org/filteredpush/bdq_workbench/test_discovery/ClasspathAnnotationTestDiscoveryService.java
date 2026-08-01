package org.filteredpush.bdq_workbench.test_discovery;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestType;
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
        try (var scanResult = new ClassGraph()
                .overrideClassLoaders(cl)
                .ignoreClassVisibility()
                .ignoreMethodVisibility()
                .enableAllInfo()
                .acceptPackages(scanPackages.toArray(String[]::new))
                .scan()) {
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                Class<?> clazz = classInfo.loadClass();
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
        return discovered;
    }

    private static String readProvides(Annotation[] annotations) {
        return readAnnotationValue(annotations, "Provides", "value");
    }

    private static String readProvidesVersion(Annotation[] annotations) {
        return readAnnotationValue(annotations, "ProvidesVersion", "value");
    }

    private static String readSpecification(Annotation[] annotations) {
        return readAnnotationValue(annotations, "Specification", "value");
    }

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

    private static Phase inferPhase(TestType testType) {
        return testType == TestType.AMENDMENT ? Phase.AMENDMENT : Phase.PRE_AMENDMENT;
    }

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

    private static String readAnnotationProperty(Annotation annotation, String name) {
        try {
            Object value = annotation.annotationType().getMethod(name).invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
