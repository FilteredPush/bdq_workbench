package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

class ReflectionExecutionAdapterTest {

    @Test
    void adaptsDqResponseFieldsIntoNormalizedResponse() throws Exception {
        ReflectionExecutionAdapter adapter = new ReflectionExecutionAdapter();
        Method method = Impl.class.getMethod("validate", String.class, Integer.class);
        MethodParameter actedUpon = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:eventDate", String.class.getName(), true);
        MethodParameter parameter = new MethodParameter(1, "p1", ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class.getName(), true);
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:1",
                TestType.VALIDATION,
                Impl.class.getName(),
                "validate",
                Phase.PRE_AMENDMENT,
                Map.of("bdq:latestValidDate", "2026"),
                BindingStatus.BOUND,
                ParameterizationCapability.PARAMETERIZED_ONLY,
                "parameterized",
                false,
                List.of(
                        new BoundMethodParameter(actedUpon, "dwc:eventDate", null, true, "Mapped"),
                        new BoundMethodParameter(parameter, "bdq:latestValidDate", "2026", true, "Provided")),
                List.of());
        DiscoveredImplementation implementation = new DiscoveredImplementation(
                "urn:test:1",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Impl.class.getName(),
                "validate",
                null,
                List.of(actedUpon, parameter),
                new Impl(),
                method);

        var response = adapter.execute(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01")),
                binding,
                implementation);

        assertThat(response.responseStatus()).isEqualTo("RUN_HAS_RESULT");
        assertThat(response.responseResult()).isEqualTo("COMPLIANT");
        assertThat(response.comment()).isEqualTo("checked");
        assertThat(response.parameters()).containsEntry("bdq:latestValidDate", "2026");
        assertThat(response.startedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void executionTraceCapturesBindingsAndRawReturnValue() throws Exception {
        ReflectionExecutionAdapter adapter = new ReflectionExecutionAdapter();
        Method method = Impl.class.getMethod("validate", String.class, Integer.class);
        MethodParameter actedUpon = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:eventDate", String.class.getName(), true);
        MethodParameter parameter = new MethodParameter(1, "p1", ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class.getName(), true);
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:1",
                TestType.VALIDATION,
                Impl.class.getName(),
                "validate",
                Phase.PRE_AMENDMENT,
                Map.of("bdq:latestValidDate", "2026"),
                BindingStatus.BOUND,
                ParameterizationCapability.PARAMETERIZED_ONLY,
                "parameterized",
                false,
                List.of(
                        new BoundMethodParameter(actedUpon, "dwc:eventDate", null, true, "Mapped"),
                        new BoundMethodParameter(parameter, "bdq:latestValidDate", "2026", true, "Provided")),
                List.of());
        DiscoveredImplementation implementation = new DiscoveredImplementation(
                "urn:test:1",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Impl.class.getName(),
                "validate",
                null,
                List.of(actedUpon, parameter),
                new Impl(),
                method);

        ReflectionExecutionAdapter.ExecutionTrace trace = adapter.executeWithTrace(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01")),
                binding,
                implementation);

        assertThat(trace.argumentTraces()).hasSize(2);
        assertThat(trace.argumentTraces().get(0).rawValue()).isEqualTo("2025-01-01");
        assertThat(trace.argumentTraces().get(1).convertedValue()).isEqualTo("2026");
        assertThat(trace.rawReturnType()).isEqualTo(StubDQResponse.class.getName());
        assertThat(trace.rawReturnValue()).contains("RUN_HAS_RESULT").contains("COMPLIANT");
    }

    @Test
    void omitsResponseResultWhenDqResponseHasNoValue() throws Exception {
        ReflectionExecutionAdapter adapter = new ReflectionExecutionAdapter();
        Method method = Impl.class.getMethod("prerequisiteOnly", String.class);
        MethodParameter actedUpon = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:eventDate", String.class.getName(), true);
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:missing-result",
                TestType.VALIDATION,
                Impl.class.getName(),
                "prerequisiteOnly",
                Phase.PRE_AMENDMENT,
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "default",
                true,
                List.of(new BoundMethodParameter(actedUpon, "dwc:eventDate", null, true, "Mapped")),
                List.of());
        DiscoveredImplementation implementation = new DiscoveredImplementation(
                "urn:test:missing-result",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Impl.class.getName(),
                "prerequisiteOnly",
                null,
                List.of(actedUpon),
                new Impl(),
                method);

        var response = adapter.execute(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "not-a-date")),
                binding,
                implementation);

        assertThat(response.responseStatus()).isEqualTo("INTERNAL_PREREQUISITES_NOT_MET");
        assertThat(response.responseResult()).isNull();
        assertThat(response.message()).isEqualTo("INTERNAL_PREREQUISITES_NOT_MET");
    }

    @Test
    void returnsErrorResponseWhenBoundParameterCannotBeConverted() throws Exception {
        ReflectionExecutionAdapter adapter = new ReflectionExecutionAdapter();
        Method method = Impl.class.getMethod("validate", String.class, Integer.class);
        MethodParameter actedUpon = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "dwc:eventDate", String.class.getName(), true);
        MethodParameter parameter = new MethodParameter(1, "p1", ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class.getName(), true);
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:bad-parameter",
                TestType.VALIDATION,
                Impl.class.getName(),
                "validate",
                Phase.PRE_AMENDMENT,
                Map.of("bdq:latestValidDate", "not-a-number"),
                BindingStatus.BOUND,
                ParameterizationCapability.PARAMETERIZED_ONLY,
                "parameterized",
                false,
                List.of(
                        new BoundMethodParameter(actedUpon, "dwc:eventDate", null, true, "Mapped"),
                        new BoundMethodParameter(parameter, "bdq:latestValidDate", "not-a-number", true, "Provided")),
                List.of());
        DiscoveredImplementation implementation = new DiscoveredImplementation(
                "urn:test:bad-parameter",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Impl.class.getName(),
                "validate",
                null,
                List.of(actedUpon, parameter),
                new Impl(),
                method);

        ReflectionExecutionAdapter.ExecutionTrace trace = adapter.executeWithTrace(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01")),
                binding,
                implementation);

        assertThat(trace.response().status()).isEqualTo(OutcomeStatus.ERROR);
        assertThat(trace.response().responseStatus()).isEqualTo("ERROR");
        assertThat(trace.response().message()).contains("NumberFormatException");
        assertThat(trace.argumentTraces()).hasSize(2);
        assertThat(trace.argumentTraces().get(1).reason()).contains("Failed to convert value");
    }

    @Test
    void returnsControlledErrorWhenBindingMetadataDoesNotMatchReflectedMethod() throws Exception {
        ReflectionExecutionAdapter adapter = new ReflectionExecutionAdapter();
        Method method = Impl.class.getMethod("validateFour", String.class, String.class, String.class, String.class);
        MethodParameter latitude = new MethodParameter(0, "p0", ParameterRole.ACTED_UPON, "decimalLatitude", String.class.getName(), true);
        MethodParameter longitude = new MethodParameter(1, "p1", ParameterRole.ACTED_UPON, "decimalLongitude", String.class.getName(), true);
        MethodParameter countryCode = new MethodParameter(2, "p2", ParameterRole.ACTED_UPON, "countryCode", String.class.getName(), true);
        MethodParameter sourceAuthority = new MethodParameter(3, "p3", ParameterRole.PARAMETER, "bdq:sourceAuthority", String.class.getName(), true);
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:mismatch",
                TestType.VALIDATION,
                Impl.class.getName(),
                "validateFour",
                Phase.PRE_AMENDMENT,
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.PARAMETERIZED_ONLY,
                "overloaded",
                true,
                List.of(
                        new BoundMethodParameter(latitude, "decimalLatitude", null, true, "Mapped"),
                        new BoundMethodParameter(longitude, "decimalLongitude", null, true, "Mapped"),
                        new BoundMethodParameter(countryCode, "countryCode", null, true, "Mapped")),
                List.of());
        DiscoveredImplementation implementation = new DiscoveredImplementation(
                "urn:test:mismatch",
                null,
                TestType.VALIDATION,
                Phase.PRE_AMENDMENT,
                Impl.class.getName(),
                "validateFour",
                null,
                List.of(latitude, longitude, countryCode, sourceAuthority),
                new Impl(),
                method);

        ReflectionExecutionAdapter.ExecutionTrace trace = adapter.executeWithTrace(
                new CanonicalRecord(
                        "r1",
                        Map.of(
                                "decimalLatitude", "1",
                                "decimalLongitude", "2",
                                "countryCode", "FR")),
                binding,
                implementation);

        assertThat(trace.response().status()).isEqualTo(OutcomeStatus.ERROR);
        assertThat(trace.response().responseStatus()).isEqualTo("ERROR");
        assertThat(trace.response().message())
                .contains("Invocation metadata mismatch")
                .contains("binding argument count=3, reflected argument count=4")
                .contains("likely cause=overloaded method ambiguity or stale discovered metadata");
    }

    static class Impl {
        public StubDQResponse validate(String eventDate, Integer latestValidDate) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", "checked");
        }

        public StubDQResponse.StubDQResponseWithoutValue prerequisiteOnly(String eventDate) {
            return new StubDQResponse.StubDQResponseWithoutValue("INTERNAL_PREREQUISITES_NOT_MET", "{}", null);
        }

        public StubDQResponse validateFour(String decimalLatitude, String decimalLongitude, String countryCode, String sourceAuthority) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", sourceAuthority);
        }
    }

    static class StubDQResponse {
        private final StubResultState resultState;
        private final StubResultValue value;
        private final String comment;

        StubDQResponse(String status, String result, String comment) {
            this.resultState = new StubResultState(status);
            this.value = new StubResultValue(result);
            this.comment = comment;
        }

        static class StubDQResponseWithoutValue {
            private final StubResultState resultState;
            private final String rendered;
            private final String comment;

            StubDQResponseWithoutValue(String status, String rendered, String comment) {
                this.resultState = new StubResultState(status);
                this.rendered = rendered;
                this.comment = comment;
            }

            public StubResultState getResultState() {
                return resultState;
            }

            public Object getValue() {
                return null;
            }

            public String getComment() {
                return comment;
            }

            @Override
            public String toString() {
                return rendered;
            }
        }

        public StubResultState getResultState() {
            return resultState;
        }

        public StubResultValue getValue() {
            return value;
        }

        public String getComment() {
            return comment;
        }

        @Override
        public String toString() {
            return "StubDQResponse[" + resultState.label + "," + value.object + "," + comment + "]";
        }
    }

    static class StubResultState {
        private final String label;

        StubResultState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    static class StubResultValue {
        private final Object object;

        StubResultValue(Object object) {
            this.object = object;
        }

        public Object getObject() {
            return object;
        }
    }
}
