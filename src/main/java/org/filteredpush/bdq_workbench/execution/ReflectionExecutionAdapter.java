/** ReflectionExecutionAdapter.java
 *
 * Reflection-based ExecutionAdapter that invokes ffdq-compatible test implementation methods, converts and binds their arguments, and translates their return values into a Response.
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

/**
 * Reflection-based {@link ExecutionAdapter} for invoking ffdq-compatible test implementation
 * methods.
 *
 * <p>For each invocation, this adapter uses a record's {@link ImplementationBinding} (the
 * per-parameter {@link BoundMethodParameter} list in particular) to assemble a reflective
 * argument array from the record's Darwin Core term values and any supplied parameter values,
 * invokes the {@link DiscoveredImplementation}'s target method via {@link java.lang.reflect.Method#invoke},
 * and then inspects the ffdq result object returned (via its {@code getResultState()},
 * {@code getValue()}, and {@code getComment()} accessor methods, called reflectively so that this
 * adapter does not need a compile-time dependency on the ffdq result types) to build a
 * {@link Response}.
 *
 * <p>Amendments are detected two ways: from a {@code Map} returned as the result's value object,
 * and — for AMENDMENT-phase bindings — by diffing the record's term values before and after
 * invocation, since some implementations mutate the record map in place rather than returning
 * changed values. Any exception raised during argument conversion or method invocation is caught
 * and converted into an {@link OutcomeStatus#ERROR} response rather than propagated, so that
 * {@link ParallelPhaseExecutionService} can execute many records without per-invocation exception
 * handling.
 *
 * <p>{@link #executeWithTrace(CanonicalRecord, ImplementationBinding, DiscoveredImplementation)}
 * exposes additional diagnostic detail (the resolved arguments and raw return value) beyond what
 * {@link #execute} returns, intended for tooling that needs to explain why a particular invocation
 * produced its result.
 */
public class ReflectionExecutionAdapter implements ExecutionAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ReflectionExecutionAdapter.class);

    /**
     * Invokes the bound implementation against the record and returns only its {@link Response},
     * discarding the additional invocation diagnostics available from
     * {@link #executeWithTrace(CanonicalRecord, ImplementationBinding, DiscoveredImplementation)}.
     *
     * @param record the canonical record to test or amend
     * @param binding the resolved binding identifying the test, implementation method, phase, and
     *     parameters to use
     * @param implementation the discovered implementation metadata to invoke; if {@code null}, an
     *     {@link OutcomeStatus#ERROR} response is returned instead of invoking anything
     * @return the response describing the outcome of invoking the test against the record
     */
    @Override
    public Response execute(CanonicalRecord record, ImplementationBinding binding, DiscoveredImplementation implementation) {
        return executeWithTrace(record, binding, implementation).response();
    }

    /**
     * Invokes the bound implementation against the record, as {@link #execute} does, but also
     * returns the resolved argument traces and the raw return value/type of the invocation, for
     * callers that need to explain or debug how a particular {@link Response} was produced.
     *
     * <p>If {@code implementation} is {@code null}, argument binding fails, or the invoked method
     * throws, this method does not propagate the failure; instead it returns an
     * {@link ExecutionTrace} whose {@link Response} has {@link OutcomeStatus#ERROR} and whose
     * message describes the failure (including, where available, the argument traces resolved up
     * to the point of failure).
     *
     * @param record the canonical record to test or amend
     * @param binding the resolved binding identifying the test, implementation method, phase, and
     *     parameters to use
     * @param implementation the discovered implementation metadata (target instance and
     *     reflective method) to invoke; may be {@code null} if no matching implementation was
     *     discovered
     * @return a trace containing the response, the resolved argument bindings (as far as they
     *     could be resolved), and the raw return type/value of the invocation, if any
     */
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

    /**
     * Resolves the reflective argument array for invoking {@code binding}'s implementation
     * method, by walking its {@link BoundMethodParameter} list and, for each parameter, reading
     * the appropriate raw value (a record term for {@code ACTED_UPON}/{@code CONSULTED} roles, a
     * supplied value for {@code PARAMETER}, or the whole term map/parameter map for the legacy
     * whole-record/whole-parameters roles) and converting it to the parameter's declared type.
     *
     * @param recordTerms the record's term values to read {@code ACTED_UPON}/{@code CONSULTED}
     *     parameters and the legacy whole-record parameter from
     * @param binding the binding whose {@link ImplementationBinding#parameterBindings()} describe
     *     the method's parameters and how each is sourced
     * @return the resolved argument array (ready to pass to {@link java.lang.reflect.Method#invoke})
     *     together with a trace of how each argument was resolved
     * @throws ArgumentBindingException if a raw value cannot be converted to its parameter's
     *     declared type
     */
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

    /**
     * Converts a raw string term/parameter value to the declared parameter type of a reflective
     * method argument, supporting {@code String} and the boxed/primitive numeric and boolean
     * types; any other type name is passed through unconverted as the raw string.
     *
     * @param rawValue the raw string value to convert; may be {@code null}
     * @param typeName the fully qualified boxed type name, or primitive type name, to convert to
     * @return the converted value, or {@code null} if {@code rawValue} is {@code null}
     */
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

    /**
     * Maps an ffdq response status string (as extracted from the invocation result's
     * {@code getResultState()}/{@code getLabel()}) to the workbench's {@link OutcomeStatus}.
     *
     * <p>When no response status could be extracted, an AMENDMENT-phase binding that produced
     * amendments is treated as {@link OutcomeStatus#AMENDED}; otherwise the outcome falls back to
     * {@link OutcomeStatus#FAILED} if the raw result is {@code Boolean.FALSE}, or
     * {@link OutcomeStatus#PASSED} otherwise.
     *
     * @param binding the binding that was invoked, used to check whether this is an AMENDMENT
     *     phase invocation
     * @param responseStatus the ffdq response status label extracted from the result, or
     *     {@code null} if none could be extracted
     * @param amendments the amendments extracted from the result/record diff
     * @param result the raw object returned by the invoked method
     * @return the mapped outcome status
     */
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

    /**
     * Reflectively reads the ffdq response status label from the invocation result, by calling
     * {@code getResultState()} and then {@code getLabel()} on the returned state object.
     *
     * @param result the raw object returned by the invoked method
     * @param binding the binding that was invoked, used to fall back to {@code "AMENDED"} when no
     *     status is available but this is an AMENDMENT-phase invocation that produced amendments
     * @param amendments the amendments extracted from the result/record diff
     * @return the response status label, or {@code null} if it could not be determined
     */
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

    /**
     * Reflectively reads the ffdq response result value from the invocation result, by calling
     * {@code getValue()} and then, if present, {@code getObject()} on the returned value object.
     *
     * @param result the raw object returned by the invoked method
     * @param amendments the amendments extracted from the result/record diff, used as a fallback
     *     string representation when the result carries no explicit value
     * @return the string representation of the response result, or {@code null} if there is
     *     neither a value nor any amendments
     */
    private static String extractResponseResult(Object result, Map<String, String> amendments) {
        Object value = invokeNoArg(result, "getValue");
        if (value == null) {
            return amendments.isEmpty() ? null : amendments.toString();
        }
        Object object = invokeNoArg(value, "getObject");
        return stringify(object == null ? value : object);
    }

    /**
     * Builds a fallback response message when the invocation result carried no explicit comment,
     * from the response status and result, or, failing that, the raw result's string form.
     *
     * @param responseStatus the extracted response status label, or {@code null}
     * @param responseResult the extracted response result, or {@code null}
     * @param result the raw object returned by the invoked method
     * @return a human-readable fallback message describing the outcome
     */
    private static String defaultMessage(String responseStatus, String responseResult, Object result) {
        if (responseStatus != null && !responseStatus.isBlank()) {
            return responseResult == null || responseResult.isBlank()
                    ? responseStatus
                    : responseStatus + " " + responseResult;
        }
        return stringify(result);
    }

    /**
     * Determines the term amendments produced by an invocation, combining two sources: any
     * {@code Map} returned as the result's value object (via {@code getValue()}/{@code getObject()}),
     * and, for AMENDMENT-phase bindings, a diff between the record's term values before and after
     * invocation — covering implementations that mutate the supplied term map in place rather than
     * returning changed values as part of the result.
     *
     * @param result the raw object returned by the invoked method
     * @param originalTerms the record's term values captured before invocation
     * @param invocationTerms the (possibly mutated) term map that was passed into the invocation
     * @param binding the binding that was invoked, used to determine whether record-diffing
     *     applies (AMENDMENT phase only)
     * @return the combined amendments, keyed by term name; values from the result's map take
     *     precedence over the before/after diff for the same term
     */
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

    /**
     * Reflectively reads the ffdq response comment from the invocation result, by calling
     * {@code getComment()} on it.
     *
     * @param result the raw object returned by the invoked method
     * @return the comment string, or {@code null} if the result has no comment (or is itself
     *     {@code null})
     */
    private static String extractComment(Object result) {
        Object comment = invokeNoArg(result, "getComment");
        return comment == null ? null : comment.toString();
    }

    /**
     * Invokes a no-argument accessor method on {@code target} by name, reflectively, returning
     * {@code null} rather than throwing if the target is {@code null} or the method does not
     * exist or cannot be invoked. Used to read ffdq result accessors without a compile-time
     * dependency on the ffdq result types.
     *
     * @param target the object to invoke the method on; may be {@code null}
     * @param methodName the name of the no-argument method to invoke
     * @return the method's return value, or {@code null} if the target is {@code null} or the
     *     call fails for any reflective reason
     */
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

    /**
     * Renders a value via {@link Object#toString()}, returning {@code null} rather than
     * throwing/failing when the value itself is {@code null}.
     *
     * @param value the value to render; may be {@code null}
     * @return the value's string form, or {@code null} if {@code value} is {@code null}
     */
    private static String stringify(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Builds an {@link OutcomeStatus#ERROR} response describing a failure to execute a binding
     * against a record, using {@link #describeError(Throwable)} for the response's comment and
     * message.
     *
     * @param record the record the invocation was attempted against
     * @param binding the binding that failed to execute
     * @param start the instant the invocation attempt began
     * @param error the failure that occurred
     * @return an error response for the given record/binding
     */
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

    /**
     * Renders a human-readable description of a failure, combining the exception's class/message
     * with its cause's class/message when the failure has a distinct cause.
     *
     * @param error the failure to describe
     * @return a description of the form {@code "ExceptionClass: message"}, with
     *     {@code " (caused by CauseClass: causeMessage)"} appended when a distinct cause is
     *     present
     */
    private static String describeError(Throwable error) {
        String message = error.getMessage();
        String description = message == null || message.isBlank()
                ? error.getClass().getName()
                : error.getClass().getSimpleName() + ": " + message;
        Throwable cause = error.getCause();
        if (cause == null || cause == error) {
            return description;
        }
        String causeMessage = cause.getMessage();
        String causeDescription = causeMessage == null || causeMessage.isBlank()
                ? cause.getClass().getName()
                : cause.getClass().getSimpleName() + ": " + causeMessage;
        return description + " (caused by " + causeDescription + ")";
    }

    /**
     * The resolved reflective argument array for one invocation, together with a trace of how
     * each argument was determined.
     *
     * @param arguments the argument array, ready to pass to {@link java.lang.reflect.Method#invoke}
     * @param argumentTraces a diagnostic trace of how each argument was resolved, in parameter
     *     order
     */
    private record InvocationPlan(Object[] arguments, List<ArgumentTrace> argumentTraces) {
        /**
         * @return an empty invocation plan, used as a placeholder before argument binding has been
         *     attempted (e.g. when no implementation was discovered)
         */
        private static InvocationPlan empty() {
            return new InvocationPlan(new Object[0], List.of());
        }
    }

    /**
     * Diagnostic detail produced by {@link #executeWithTrace} alongside its {@link Response},
     * exposing the resolved invocation arguments and the raw return value, for tooling that needs
     * to explain how a particular response was produced.
     *
     * @param response the response produced by the invocation (or a synthesized error response)
     * @param argumentTraces a trace of how each of the invoked method's arguments was resolved,
     *     as far as argument binding proceeded before any failure
     * @param rawReturnType the fully qualified class name of the value returned by the invoked
     *     method, or {@code null} if the method returned {@code null} or was never invoked
     * @param rawReturnValue the string form of the value returned by the invoked method, or
     *     {@code null} under the same conditions as {@code rawReturnType}
     */
    public record ExecutionTrace(
            Response response,
            List<ArgumentTrace> argumentTraces,
            String rawReturnType,
            String rawReturnValue) {
    }

    /**
     * Signals that a raw term/parameter value could not be converted to a bound method
     * parameter's declared type, carrying the argument traces resolved up to the point of
     * failure so the caller can still report partial diagnostic detail.
     */
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

    /**
     * Diagnostic trace of how a single reflective invocation argument was resolved.
     *
     * @param parameterName the name of the implementation method's parameter
     * @param role the parameter's role (e.g. acted-upon term, consulted term, supplied
     *     parameter, or legacy whole-record/whole-parameters)
     * @param source the resolved source the raw value was read from (e.g. the Darwin Core term
     *     name), where applicable
     * @param rawValue the raw string value read from the record/parameters before conversion
     * @param convertedValue the string form of the value after conversion to the parameter's
     *     declared type, or {@code null} if conversion did not complete
     * @param reason a human-readable explanation of how the source was resolved, or of why
     *     conversion failed
     */
    public record ArgumentTrace(
            String parameterName,
            ParameterRole role,
            String source,
            String rawValue,
            String convertedValue,
            String reason) {
    }
}
