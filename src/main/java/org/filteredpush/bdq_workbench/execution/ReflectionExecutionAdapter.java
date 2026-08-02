package org.filteredpush.bdq_workbench.execution;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        return executeWithTrace(record, binding, implementation).response();
    }

    public ExecutionTrace executeWithTrace(
            CanonicalRecord record,
            ImplementationBinding binding,
            DiscoveredImplementation implementation) {
        Instant start = Instant.now();
        Map<String, String> originalTerms = new LinkedHashMap<>(record.terms());
        Map<String, String> invocationTerms = new LinkedHashMap<>(record.terms());
        InvocationPlan invocation = InvocationPlan.empty();
        if (implementation == null) {
            IllegalStateException error = new IllegalStateException(
                    "No discovered implementation metadata available for "
                            + binding.implementationClass()
                            + "#"
                            + binding.implementationMethod());
            LOG.error("Unable to execute {}.{} for record {}: {}",
                    binding.implementationClass(), binding.implementationMethod(), record.id(), error.getMessage(), error);
            return new ExecutionTrace(
                    errorResponse(record, binding, start, error),
                    List.of(),
                    null,
                    null);
        }
        try {
            invocation = buildArguments(invocationTerms, binding);
            LOG.debug("Invoking {}.{} for record {} in phase {} with arguments {}",
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    record.id(),
                    binding.phase(),
                    invocation.argumentTraces());
            Object result = implementation.method().invoke(implementation.target(), invocation.arguments());
            Map<String, String> amendments = extractAmendments(result, originalTerms, invocationTerms, binding);
            String responseStatus = extractResponseStatus(result, binding, amendments);
            String responseResult = extractResponseResult(result, amendments);
            String comment = extractComment(result);
            OutcomeStatus status = mapOutcomeStatus(binding, responseStatus, amendments, result);
            String message = comment == null || comment.isBlank()
                    ? defaultMessage(responseStatus, responseResult, result)
                    : comment;
            LOG.debug("Executed {}.{} for record {} with status {} / {}, result {}, comment {}",
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    record.id(),
                    status,
                    responseStatus,
                    responseResult,
                    comment);
            return new ExecutionTrace(new Response(
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
                    Instant.now()),
                    List.copyOf(invocation.argumentTraces()),
                    result == null ? null : result.getClass().getName(),
                    stringify(result));
        } catch (ArgumentBindingException e) {
            LOG.error("Error binding arguments for {}.{} on record {} with traces {}: {}",
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    record.id(),
                    e.argumentTraces(),
                    e.getMessage(),
                    e);
            return new ExecutionTrace(
                    errorResponse(record, binding, start, e),
                    e.argumentTraces(),
                    null,
                    null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            LOG.error("Error executing {}.{} for record {} with arguments {}: {}",
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    record.id(),
                    invocation.argumentTraces(),
                    cause.getMessage(),
                    cause);
            return new ExecutionTrace(
                    errorResponse(record, binding, start, cause),
                    List.copyOf(invocation.argumentTraces()),
                    cause.getClass().getName(),
                    cause.toString());
        } catch (Exception e) {
            LOG.error("Error executing {}.{} for record {} with arguments {}: {}",
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    record.id(),
                    invocation.argumentTraces(),
                    e.getMessage(),
                    e);
            return new ExecutionTrace(
                    errorResponse(record, binding, start, e),
                    List.copyOf(invocation.argumentTraces()),
                    e.getClass().getName(),
                    e.toString());
        }
    }

    private InvocationPlan buildArguments(Map<String, String> recordTerms, ImplementationBinding binding) {
        Object[] arguments = new Object[binding.parameterBindings().size()];
        List<ArgumentTrace> argumentTraces = new ArrayList<>();
        for (BoundMethodParameter parameter : binding.parameterBindings()) {
            String rawValue = switch (parameter.parameter().role()) {
                case ACTED_UPON, CONSULTED -> recordTerms.get(parameter.resolvedSource());
                case PARAMETER -> parameter.suppliedValue();
                case LEGACY_RECORD -> recordTerms.toString();
                case LEGACY_PARAMETERS -> binding.parameters().toString();
            };
            try {
                Object argument = switch (parameter.parameter().role()) {
                    case ACTED_UPON, CONSULTED -> convertValue(rawValue, parameter.parameter().typeName());
                    case PARAMETER -> convertValue(rawValue, parameter.parameter().typeName());
                    case LEGACY_RECORD -> recordTerms;
                    case LEGACY_PARAMETERS -> binding.parameters();
                };
                arguments[parameter.parameter().index()] = argument;
                argumentTraces.add(new ArgumentTrace(
                        parameter.parameter().name(),
                        parameter.parameter().role(),
                        parameter.resolvedSource(),
                        rawValue,
                        stringify(argument),
                        parameter.reason()));
            } catch (RuntimeException e) {
                argumentTraces.add(new ArgumentTrace(
                        parameter.parameter().name(),
                        parameter.parameter().role(),
                        parameter.resolvedSource(),
                        rawValue,
                        null,
                        "Failed to convert value for " + parameter.resolvedSource()
                                + " to " + parameter.parameter().typeName()
                                + ": " + e.getMessage()));
                throw new ArgumentBindingException(
                        "Failed to convert bound value for " + parameter.resolvedSource()
                                + " to " + parameter.parameter().typeName(),
                        e,
                        List.copyOf(argumentTraces));
            }
        }
        return new InvocationPlan(arguments, List.copyOf(argumentTraces));
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
            return amendments.isEmpty() ? null : amendments.toString();
        }
        Object object = invokeNoArg(value, "getObject");
        return stringify(object == null ? value : object);
    }

    private static String defaultMessage(String responseStatus, String responseResult, Object result) {
        if (responseStatus != null && !responseStatus.isBlank()) {
            return responseResult == null || responseResult.isBlank()
                    ? responseStatus
                    : responseStatus + " " + responseResult;
        }
        return stringify(result);
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
        String errorMessage = describeError(error);
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
                errorMessage,
                errorMessage,
                Map.of(),
                start,
                Instant.now());
    }

    private static String describeError(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    private record InvocationPlan(Object[] arguments, List<ArgumentTrace> argumentTraces) {
        private static InvocationPlan empty() {
            return new InvocationPlan(new Object[0], List.of());
        }
    }

    public record ExecutionTrace(
            Response response,
            List<ArgumentTrace> argumentTraces,
            String rawReturnType,
            String rawReturnValue) {
    }

    private static final class ArgumentBindingException extends RuntimeException {
        private final List<ArgumentTrace> argumentTraces;

        private ArgumentBindingException(String message, Throwable cause, List<ArgumentTrace> argumentTraces) {
            super(message, cause);
            this.argumentTraces = argumentTraces;
        }

        private List<ArgumentTrace> argumentTraces() {
            return argumentTraces;
        }
    }

    public record ArgumentTrace(
            String parameterName,
            ParameterRole role,
            String source,
            String rawValue,
            String convertedValue,
            String reason) {
    }
}
