package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

class ParallelPhaseExecutionServiceTest {

    @Test
    void orchestratesPhasesNormalizesResponsesAndAppliesAmendments() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method pre = Impl.class.getMethod("pre", String.class);
        Method amend = Impl.class.getMethod("amend", String.class);
        Method post = Impl.class.getMethod("post", String.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("t1", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, Impl.class.getName(), "pre", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new Impl(), pre),
                new DiscoveredImplementation("t2", null, TestType.AMENDMENT, Phase.AMENDMENT, Impl.class.getName(), "amend", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new Impl(), amend),
                new DiscoveredImplementation("t3", null, TestType.VALIDATION, Phase.POST_AMENDMENT, Impl.class.getName(), "post", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new Impl(), post));

        List<ImplementationBinding> bindings = List.of(
                binding("t1", TestType.VALIDATION, "pre", Phase.PRE_AMENDMENT),
                binding("t2", TestType.AMENDMENT, "amend", Phase.AMENDMENT),
                binding("t3", TestType.VALIDATION, "post", Phase.POST_AMENDMENT));

        RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "orig"))));

        var responses = service.execute(dataset, bindings, discovered);

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(Response::phase).containsExactly(Phase.PRE_AMENDMENT, Phase.AMENDMENT, Phase.POST_AMENDMENT);
        assertThat(responses.get(0).responseStatus()).isEqualTo("RUN_HAS_RESULT");
        assertThat(responses.get(0).responseResult()).isEqualTo("COMPLIANT");
        assertThat(responses.get(0).comment()).isEqualTo("ok");
        assertThat(responses.get(1).amendments()).containsEntry("dwc:eventDate", "changed");
        assertThat(responses.get(2).responseResult()).isEqualTo("COMPLIANT");
        assertThat(dataset.records().get(0).terms()).containsEntry("dwc:eventDate", "orig");
    }

    private static MethodParameter parameter(int index, ParameterRole role, String source, Class<?> type) {
        return new MethodParameter(index, "p" + index, role, source, type.getName(), true);
    }

    private static ImplementationBinding binding(String testId, TestType testType, String method, Phase phase) {
        MethodParameter parameter = parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class);
        BoundMethodParameter bound = new BoundMethodParameter(parameter, "dwc:eventDate", null, true, "Mapped");
        return new ImplementationBinding(
                testId,
                testType,
                Impl.class.getName(),
                method,
                phase,
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "test",
                true,
                List.of(bound),
                List.of());
    }

    static class Impl {
        public StubDQResponse pre(String eventDate) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", "ok", Map.of());
        }

        public StubDQResponse amend(String eventDate) {
            return new StubDQResponse("AMENDED", Map.of("dwc:eventDate", "changed"), "updated", Map.of("dwc:eventDate", "changed"));
        }

        public StubDQResponse post(String eventDate) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", eventDate, Map.of());
        }
    }

    static class StubDQResponse {
        private final StubResultState resultState;
        private final StubResultValue value;
        private final String comment;

        StubDQResponse(String status, Object object, String comment, Map<String, String> ignored) {
            this.resultState = new StubResultState(status);
            this.value = new StubResultValue(object);
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
