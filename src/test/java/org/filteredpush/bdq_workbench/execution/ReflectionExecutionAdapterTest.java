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

    static class Impl {
        public StubDQResponse validate(String eventDate, Integer latestValidDate) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", "checked");
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
