package org.filteredpush.bdq_workbench.execution;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reflection-based adapter for ffdq-compatible implementation invocation. */
public class ReflectionExecutionAdapter implements ExecutionAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ReflectionExecutionAdapter.class);

    @Override
    public Response execute(CanonicalRecord record, ImplementationBinding binding, DiscoveredImplementation implementation) {
        Instant start = Instant.now();
        Map<String, String> originalTerms = new LinkedHashMap<>(record.terms());
        Map<String, String> invocationTerms = new LinkedHashMap<>(record.terms());
        try {
            Object result = implementation.method().invoke(implementation.target(), buildArguments(invocationTerms, binding));
            Map<String, String> amendments = extractAmendments(result, originalTerms, invocationTerms, binding);
            String responseStatus = extractResponseStatus(result, binding, amendments);
            String responseResult = extractResponseResult(result, amendments);
            String comment = extractComment(result);
            OutcomeStatus status = mapOutcomeStatus(binding, responseStatus, amendments, result);
            String message = comment == null || comment.isBlank()
                    ? responseResult == null ? responseStatus : responseStatus + " " + responseResult
                    : comment;
            LOG.debug("Executed {}.{} for record {} with status {} / {}",
                    binding.implementationClass(), binding.implementationMethod(), record.id(), status, responseStatus);
            return new Response(
                    record.id(),
                    binding.testId(),
                    binding.testType(),
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    binding.phase(),
                    binding.parameters(),
                    status,
                    responseStatus,
                    responseResult,
                    comment,
                    message,
                    Map.copyOf(amendments),
                    start,
                    Instant.now());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            LOG.error("Error executing {}.{} for record {}: {}",
                    binding.implementationClass(), binding.implementationMethod(), record.id(), cause.getMessage(), cause);
            return errorResponse(record, binding, start, cause);
        } catch (Exception e) {
            LOG.error("Error executing {}.{} for record {}: {}",
                    binding.implementationClass(), binding.implementationMethod(), record.id(), e.getMessage(), e);
            return errorResponse(record, binding, start, e);
        }
    }

    private Object[] buildArguments(Map<String, String> recordTerms, ImplementationBinding binding) {
        Object[] arguments = new Object[binding.parameterBindings().size()];
        for (BoundMethodParameter parameter : binding.parameterBindings()) {
            arguments[parameter.parameter().index()] = switch (parameter.parameter().role()) {
                case ACTED_UPON, CONSULTED -> convertValue(
                        recordTerms.get(parameter.resolvedSource()),
                        parameter.parameter().typeName());
                case PARAMETER -> convertValue(parameter.suppliedValue(), parameter.parameter().typeName());
                case LEGACY_RECORD -> recordTerms;
                case LEGACY_PARAMETERS -> binding.parameters();
            };
        }
        return arguments;
    }

    private static Object convertValue(String rawValue, String typeName) {
        if (typeName.equals(String.class.getName())) {
            return rawValue;
        }
        if (rawValue == null) {
            return null;
        }
        if (typeName.equals(Integer.class.getName()) || typeName.equals("int")) {
            return Integer.valueOf(rawValue);
        }
        if (typeName.equals(Long.class.getName()) || typeName.equals("long")) {
            return Long.valueOf(rawValue);
        }
        if (typeName.equals(Double.class.getName()) || typeName.equals("double")) {
            return Double.valueOf(rawValue);
        }
        if (typeName.equals(Float.class.getName()) || typeName.equals("float")) {
            return Float.valueOf(rawValue);
        }
        if (typeName.equals(Boolean.class.getName()) || typeName.equals("boolean")) {
            return Boolean.valueOf(rawValue);
        }
        return rawValue;
    }

    private static OutcomeStatus mapOutcomeStatus(
            ImplementationBinding binding,
            String responseStatus,
            Map<String, String> amendments,
            Object result) {
        if (responseStatus == null || responseStatus.isBlank()) {
            if (binding.phase() == org.filteredpush.bdq_workbench.model.Phase.AMENDMENT && !amendments.isEmpty()) {
                return OutcomeStatus.AMENDED;
            }
            return Boolean.FALSE.equals(result) ? OutcomeStatus.FAILED : OutcomeStatus.PASSED;
        }
        return switch (responseStatus) {
            case "AMENDED", "FILLED_IN" -> OutcomeStatus.AMENDED;
            case "NOT_AMENDED", "NOT_RUN", "INTERNAL_PREREQUISITES_NOT_MET", "EXTERNAL_PREREQUISITES_NOT_MET" -> OutcomeStatus.FAILED;
            case "RUN_HAS_RESULT" -> OutcomeStatus.PASSED;
            default -> Boolean.FALSE.equals(result) ? OutcomeStatus.FAILED : OutcomeStatus.PASSED;
        };
    }

    private static String extractResponseStatus(Object result, ImplementationBinding binding, Map<String, String> amendments) {
        Object state = invokeNoArg(result, "getResultState");
        if (state == null) {
            return binding.phase() == org.filteredpush.bdq_workbench.model.Phase.AMENDMENT && !amendments.isEmpty()
                    ? "AMENDED"
                    : null;
        }
        Object label = invokeNoArg(state, "getLabel");
        return label == null ? state.toString() : label.toString();
    }

    private static String extractResponseResult(Object result, Map<String, String> amendments) {
        Object value = invokeNoArg(result, "getValue");
        if (value == null) {
            return amendments.isEmpty() ? stringify(result) : amendments.toString();
        }
        Object object = invokeNoArg(value, "getObject");
        return stringify(object == null ? value : object);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractAmendments(
            Object result,
            Map<String, String> originalTerms,
            Map<String, String> invocationTerms,
            ImplementationBinding binding) {
        Map<String, String> amendments = new LinkedHashMap<>();
        Object value = invokeNoArg(result, "getValue");
        Object object = value == null ? null : invokeNoArg(value, "getObject");
        if (object instanceof Map<?, ?> map) {
            map.forEach((key, val) -> amendments.put(String.valueOf(key), val == null ? null : String.valueOf(val)));
        }
        if (binding.phase() == org.filteredpush.bdq_workbench.model.Phase.AMENDMENT) {
            invocationTerms.forEach((key, valueAfter) -> {
                String before = originalTerms.get(key);
                if (before == null ? valueAfter != null : !before.equals(valueAfter)) {
                    amendments.putIfAbsent(key, valueAfter);
                }
            });
        }
        return amendments;
    }

    private static String extractComment(Object result) {
        Object comment = invokeNoArg(result, "getComment");
        return comment == null ? null : comment.toString();
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String stringify(Object value) {
        return value == null ? null : value.toString();
    }

    private static Response errorResponse(CanonicalRecord record, ImplementationBinding binding, Instant start, Throwable error) {
        return new Response(
                record.id(),
                binding.testId(),
                binding.testType(),
                binding.implementationClass(),
                binding.implementationMethod(),
                binding.phase(),
                binding.parameters(),
                OutcomeStatus.ERROR,
                "ERROR",
                null,
                error.getMessage(),
                error.getMessage(),
                Map.of(),
                start,
                Instant.now());
    }
}
