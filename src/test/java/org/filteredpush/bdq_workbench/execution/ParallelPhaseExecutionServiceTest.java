package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
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
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
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

        assertThat(responses).hasSize(4);
        assertThat(responses)
                .extracting(Response::phase, Response::testId, Response::comment)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Phase.PRE_AMENDMENT, "t1", "ok"),
                        org.assertj.core.groups.Tuple.tuple(Phase.AMENDMENT, "t2", "updated"),
                        org.assertj.core.groups.Tuple.tuple(Phase.POST_AMENDMENT, "t1", "ok"),
                        org.assertj.core.groups.Tuple.tuple(Phase.POST_AMENDMENT, "t3", "changed"));
        assertThat(responses.get(0).responseStatus()).isEqualTo("RUN_HAS_RESULT");
        assertThat(responses.get(0).responseResult()).isEqualTo("COMPLIANT");
        assertThat(responses.get(1).amendments()).containsEntry("dwc:eventDate", "changed");
        assertThat(responses.get(2).responseResult()).isEqualTo("COMPLIANT");
        assertThat(responses.get(3).responseResult()).isEqualTo("COMPLIANT");
        assertThat(dataset.records().get(0).terms()).containsEntry("dwc:eventDate", "orig");
    }

    @Test
    void synthesizesBuiltInCountMeasureFromValidationResponses() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method pre = CountImpl.class.getMethod("pre", String.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, CountImpl.class.getName(), "pre", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new CountImpl(), pre));

        List<ImplementationBinding> bindings = List.of(
                binding("urn:test:validation", TestType.VALIDATION, CountImpl.class.getName(), "pre", Phase.PRE_AMENDMENT),
                new ImplementationBinding(
                        "urn:test:measure",
                        TestType.MEASURE,
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                        BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        Phase.PRE_AMENDMENT,
                        new BuiltInMeasureSpec(
                                BuiltInMeasureSpec.MeasureKind.COUNT,
                                "VALIDATION_BASISOFRECORD_NOTEMPTY",
                                "urn:test:validation",
                                "COMPLIANT",
                                List.of(),
                                List.of()).asBindingParameters(),
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "built-in multi-record count",
                        true,
                        List.of(),
                        List.of("Built-in multi-record COUNT measure")));

        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "match")),
                new CanonicalRecord("r2", Map.of("dwc:eventDate", "miss"))));

        var responses = service.execute(dataset, bindings, discovered);

        assertThat(responses.stream()
                .filter(response -> response.testId().equals("urn:test:measure"))
                .toList())
                .extracting(Response::phase, Response::responseResult, Response::message)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                Phase.PRE_AMENDMENT,
                                "1",
                                "1/2 records matched COMPLIANT for VALIDATION_BASISOFRECORD_NOTEMPTY (50.0%)"),
                        org.assertj.core.groups.Tuple.tuple(
                                Phase.POST_AMENDMENT,
                                "1",
                                "1/2 records matched COMPLIANT for VALIDATION_BASISOFRECORD_NOTEMPTY (50.0%)"));
    }

    @Test
    void rerunsPreAmendmentValidationAgainstAmendedDatasetInPostAmendmentPhase() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method validate = AmendThenValidateImpl.class.getMethod("validate", String.class);
        Method amend = AmendThenValidateImpl.class.getMethod("amend", String.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, AmendThenValidateImpl.class.getName(), "validate", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new AmendThenValidateImpl(), validate),
                new DiscoveredImplementation("urn:test:amend", null, TestType.AMENDMENT, Phase.AMENDMENT, AmendThenValidateImpl.class.getName(), "amend", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new AmendThenValidateImpl(), amend));

        List<ImplementationBinding> bindings = List.of(
                binding("urn:test:validation", TestType.VALIDATION, AmendThenValidateImpl.class.getName(), "validate", Phase.PRE_AMENDMENT),
                binding("urn:test:amend", TestType.AMENDMENT, AmendThenValidateImpl.class.getName(), "amend", Phase.AMENDMENT));

        var responses = service.execute(
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "orig")))),
                bindings,
                discovered);

        assertThat(responses.stream()
                .filter(response -> response.testId().equals("urn:test:validation"))
                .toList())
                .extracting(Response::phase, Response::comment)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Phase.PRE_AMENDMENT, "orig"),
                        org.assertj.core.groups.Tuple.tuple(Phase.POST_AMENDMENT, "changed"));
    }

    @Test
    void synthesizesBuiltInQaMeasureFromValidationResponses() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method pre = QaImpl.class.getMethod("pre", String.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, QaImpl.class.getName(), "pre", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new QaImpl(), pre));

        List<ImplementationBinding> bindings = List.of(
                binding("urn:test:validation", TestType.VALIDATION, QaImpl.class.getName(), "pre", Phase.PRE_AMENDMENT),
                new ImplementationBinding(
                        "urn:test:qa",
                        TestType.MEASURE,
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                        BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        Phase.PRE_AMENDMENT,
                        new BuiltInMeasureSpec(
                                BuiltInMeasureSpec.MeasureKind.QA,
                                "VALIDATION_MINDEPTH_LESSTHAN_MAXDEPTH",
                                "urn:test:validation",
                                null,
                                List.of("COMPLIANT"),
                                List.of("INTERNAL_PREREQUISITES_NOT_MET")).asBindingParameters(),
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "built-in multi-record qa",
                        true,
                        List.of(),
                        List.of("Built-in multi-record QA measure")));

        var responses = service.execute(new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "compliant")),
                new CanonicalRecord("r2", Map.of("dwc:eventDate", "prereq")),
                new CanonicalRecord("r3", Map.of("dwc:eventDate", "bad")))), bindings, discovered);

        assertThat(responses.stream()
                .filter(response -> response.testId().equals("urn:test:qa"))
                .toList())
                .extracting(Response::phase, Response::responseResult)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Phase.PRE_AMENDMENT, "NOT_COMPLETE"),
                        org.assertj.core.groups.Tuple.tuple(Phase.POST_AMENDMENT, "NOT_COMPLETE"));
    }

    @Test
    void continuesPhaseWhenExecutionAdapterThrowsUnexpectedRuntimeException() {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(1, (record, binding, implementation) -> {
            if (binding.testId().equals("urn:test:bad")) {
                throw new IllegalStateException("boom");
            }
            return new Response(
                    record.id(),
                    binding.testId(),
                    binding.testType(),
                    binding.implementationClass(),
                    binding.implementationMethod(),
                    binding.phase(),
                    binding.parameters(),
                    OutcomeStatus.PASSED,
                    "RUN_HAS_RESULT",
                    "COMPLIANT",
                    "ok",
                    "ok",
                    Map.of(),
                    java.time.Instant.now(),
                    java.time.Instant.now());
        });

        List<ImplementationBinding> bindings = List.of(
                binding("urn:test:good", TestType.VALIDATION, "pre", Phase.PRE_AMENDMENT),
                binding("urn:test:bad", TestType.VALIDATION, "pre", Phase.PRE_AMENDMENT));

        List<Response> responses = service.execute(
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "orig")))),
                bindings,
                List.of());

        assertThat(responses).hasSize(4);
        assertThat(responses.stream()
                .filter(response -> response.phase() == Phase.PRE_AMENDMENT)
                .toList())
                .extracting(Response::testId, Response::status, Response::message)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("urn:test:good", OutcomeStatus.PASSED, "ok"),
                        org.assertj.core.groups.Tuple.tuple("urn:test:bad", OutcomeStatus.ERROR, "IllegalStateException: boom"));
    }

    private static MethodParameter parameter(int index, ParameterRole role, String source, Class<?> type) {
        return new MethodParameter(index, "p" + index, role, source, type.getName(), true);
    }

    private static ImplementationBinding binding(String testId, TestType testType, String method, Phase phase) {
        return binding(testId, testType, Impl.class.getName(), method, phase);
    }

    private static ImplementationBinding binding(String testId, TestType testType, String implementationClass, String method, Phase phase) {
        MethodParameter parameter = parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class);
        BoundMethodParameter bound = new BoundMethodParameter(parameter, "dwc:eventDate", null, true, "Mapped");
        return new ImplementationBinding(
                testId,
                testType,
                implementationClass,
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

    static class CountImpl {
        public StubDQResponse pre(String eventDate) {
            return new StubDQResponse(
                    "RUN_HAS_RESULT",
                    "match".equals(eventDate) ? "COMPLIANT" : "NOT_COMPLIANT",
                    eventDate,
                    Map.of());
        }
    }

    static class AmendThenValidateImpl {
        public StubDQResponse validate(String eventDate) {
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", eventDate, Map.of());
        }

        public StubDQResponse amend(String eventDate) {
            return new StubDQResponse("AMENDED", Map.of("dwc:eventDate", "changed"), "changed", Map.of("dwc:eventDate", "changed"));
        }
    }

    static class QaImpl {
        public StubDQResponse pre(String eventDate) {
            return switch (eventDate) {
                case "compliant" -> new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", eventDate, Map.of());
                case "prereq" -> new StubDQResponse("INTERNAL_PREREQUISITES_NOT_MET", null, eventDate, Map.of());
                default -> new StubDQResponse("RUN_HAS_RESULT", "NOT_COMPLIANT", eventDate, Map.of());
            };
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
