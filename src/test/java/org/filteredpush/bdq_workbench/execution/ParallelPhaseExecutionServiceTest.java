package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    void notifiesProgressListenerWhenEachTaskActuallyStartsAndFinishesExecuting() throws Exception {
        AtomicInteger taskStartedCalls = new AtomicInteger();
        AtomicInteger taskFinishedCalls = new AtomicInteger();
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(
                2,
                new ReflectionExecutionAdapter(),
                new ExecutionProgressListener() {
                    @Override
                    public void onTaskStarted(Phase phase) {
                        taskStartedCalls.incrementAndGet();
                    }

                    @Override
                    public void onTaskFinished(Phase phase) {
                        taskFinishedCalls.incrementAndGet();
                    }
                });
        Method pre = Impl.class.getMethod("pre", String.class);
        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("t1", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, Impl.class.getName(), "pre", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:eventDate", String.class)), new Impl(), pre));
        List<ImplementationBinding> bindings = List.of(binding("t1", TestType.VALIDATION, "pre", Phase.PRE_AMENDMENT));
        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "a")),
                new CanonicalRecord("r2", Map.of("dwc:eventDate", "b")),
                new CanonicalRecord("r3", Map.of("dwc:eventDate", "c"))));

        service.execute(dataset, bindings, discovered);

        // 3 records x 1 binding x 2 phases: a non-amendment PRE_AMENDMENT binding implicitly
        // re-runs in POST_AMENDMENT too (see bindingsForPhase), and each individual invocation is
        // reported exactly once (start and finish) as its worker thread picks it up and completes
        // it - this is what the GUI's progress display relies on for genuine concurrency
        // reporting, independent of the main thread's submission-order future collection.
        assertThat(taskStartedCalls.get()).isEqualTo(6);
        assertThat(taskFinishedCalls.get()).isEqualTo(6);
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

    @Test
    void invokesEachDistinctValueGroupOnceRatherThanOncePerRecord() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        CountingImpl impl = new CountingImpl();
        Method validate = CountingImpl.class.getMethod("validate", String.class);
        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, CountingImpl.class.getName(), "validate", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:country", String.class)), impl, validate));
        List<ImplementationBinding> bindings = List.of(
                bindingOnField("urn:test:validation", TestType.VALIDATION, CountingImpl.class.getName(), "validate", Phase.PRE_AMENDMENT, "dwc:country"));

        // 5 records, only 2 distinct dwc:country values.
        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r3", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r4", Map.of("dwc:country", "Denmark")),
                new CanonicalRecord("r5", Map.of("dwc:country", "Denmark"))));

        var responses = service.execute(dataset, bindings, discovered);

        assertThat(responses.stream()
                .filter(r -> r.testId().equals("urn:test:validation") && r.phase() == Phase.PRE_AMENDMENT))
                .as("one response per record is still produced, regardless of dedup")
                .hasSize(5);
        // A non-amendment PRE_AMENDMENT binding also implicitly re-runs in POST_AMENDMENT (see
        // bindingsForPhase), so 2 distinct values means 2 invocations per phase, 4 total.
        assertThat(impl.invocationCount.get())
                .as("only 2 distinct dwc:country values means only 2 real invocations per phase, not 5")
                .isEqualTo(4);
    }

    @Test
    void dedupProducesTheSameResponsesAsRunningOncePerRecord() throws Exception {
        CountingImpl dedupImpl = new CountingImpl();
        CountingImpl noDedupImpl = new CountingImpl();
        Method validate = CountingImpl.class.getMethod("validate", String.class);
        List<ImplementationBinding> bindings = List.of(
                bindingOnField("urn:test:validation", TestType.VALIDATION, CountingImpl.class.getName(), "validate", Phase.PRE_AMENDMENT, "dwc:country"));
        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r3", Map.of("dwc:country", "Denmark"))));

        var dedupOn = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter(), true)
                .execute(dataset.copy(), bindings, List.of(new DiscoveredImplementation(
                        "urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, CountingImpl.class.getName(), "validate", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:country", String.class)), dedupImpl, validate)));
        var dedupOff = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter(), false)
                .execute(dataset.copy(), bindings, List.of(new DiscoveredImplementation(
                        "urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, CountingImpl.class.getName(), "validate", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:country", String.class)), noDedupImpl, validate)));

        // Doubled by the implicit PRE_AMENDMENT -> POST_AMENDMENT re-run (see bindingsForPhase).
        assertThat(dedupImpl.invocationCount.get()).isEqualTo(4);
        assertThat(noDedupImpl.invocationCount.get()).isEqualTo(6);
        assertThat(stripTimestamps(dedupOn)).containsExactlyInAnyOrderElementsOf(stripTimestamps(dedupOff));
    }

    @Test
    void amendmentFanOutAppliesOnlyTheChangedTermToEveryGroupMemberLeavingOtherFieldsAlone() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method toCode = CountryToCodeImpl.class.getMethod("toCode", String.class);
        Method echo = EchoLocalityAndCodeImpl.class.getMethod("echo", String.class, String.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:amend", null, TestType.AMENDMENT, Phase.AMENDMENT, CountryToCodeImpl.class.getName(), "toCode", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:country", String.class)), new CountryToCodeImpl(), toCode),
                new DiscoveredImplementation("urn:test:echo", null, TestType.VALIDATION, Phase.POST_AMENDMENT, EchoLocalityAndCodeImpl.class.getName(), "echo", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:locality", String.class),
                                parameter(1, ParameterRole.ACTED_UPON, "dwc:countryCode", String.class)),
                        new EchoLocalityAndCodeImpl(), echo));

        ImplementationBinding amend = bindingOnField("urn:test:amend", TestType.AMENDMENT, CountryToCodeImpl.class.getName(), "toCode", Phase.AMENDMENT, "dwc:country");
        MethodParameter localityParam = parameter(0, ParameterRole.ACTED_UPON, "dwc:locality", String.class);
        MethodParameter codeParam = parameter(1, ParameterRole.ACTED_UPON, "dwc:countryCode", String.class);
        ImplementationBinding echoBinding = new ImplementationBinding(
                "urn:test:echo",
                TestType.VALIDATION,
                EchoLocalityAndCodeImpl.class.getName(),
                "echo",
                Phase.POST_AMENDMENT,
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "test",
                true,
                List.of(
                        new BoundMethodParameter(localityParam, "dwc:locality", null, true, "Mapped"),
                        new BoundMethodParameter(codeParam, "dwc:countryCode", null, true, "Mapped")),
                List.of());

        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Greenland", "dwc:locality", "A")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Greenland", "dwc:locality", "B"))));

        var responses = service.execute(dataset, List.of(amend, echoBinding), discovered);

        Map<String, String> echoedByRecord = responses.stream()
                .filter(r -> r.testId().equals("urn:test:echo"))
                .collect(java.util.stream.Collectors.toMap(Response::recordId, Response::comment));

        assertThat(echoedByRecord)
                .as("both records share the same amended countryCode but keep their own distinct locality")
                .containsEntry("r1", "A:GL")
                .containsEntry("r2", "B:GL");
    }

    @Test
    void laterAmendmentPhaseBindingSeesAnEarlierBindingsAmendmentWithinTheSamePhase() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method toCode = CountryToCodeImpl.class.getMethod("toCode", String.class);
        CodeCounterImpl codeCounter = new CodeCounterImpl();
        Method fromCode = CodeCounterImpl.class.getMethod("fromCode", String.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:amend", null, TestType.AMENDMENT, Phase.AMENDMENT, CountryToCodeImpl.class.getName(), "toCode", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:country", String.class)), new CountryToCodeImpl(), toCode),
                new DiscoveredImplementation("urn:test:note", null, TestType.AMENDMENT, Phase.AMENDMENT, CodeCounterImpl.class.getName(), "fromCode", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:countryCode", String.class)), codeCounter, fromCode));

        List<ImplementationBinding> bindings = List.of(
                bindingOnField("urn:test:amend", TestType.AMENDMENT, CountryToCodeImpl.class.getName(), "toCode", Phase.AMENDMENT, "dwc:country"),
                bindingOnField("urn:test:note", TestType.AMENDMENT, CodeCounterImpl.class.getName(), "fromCode", Phase.AMENDMENT, "dwc:countryCode"));

        // Both records start with the SAME dwc:country but deliberately DIFFERENT stale
        // dwc:countryCode placeholders; if the second binding grouped on the stale values it
        // would see 2 distinct groups instead of the 1 that reflects the first binding's amendment.
        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Greenland", "dwc:countryCode", "STALE1")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Greenland", "dwc:countryCode", "STALE2"))));

        service.execute(dataset, bindings, discovered);

        assertThat(codeCounter.invocationCount.get())
                .as("both records converge on the same amended dwc:countryCode, so the second binding sees only 1 distinct group")
                .isEqualTo(1);
    }

    @Test
    void legacyRoleBindingsAlwaysRunOncePerRecordEvenWithDuplicateValues() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        CountingLegacyImpl impl = new CountingLegacyImpl();
        Method legacy = CountingLegacyImpl.class.getMethod("validate", Map.class);
        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:legacy", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, CountingLegacyImpl.class.getName(), "validate", null,
                        List.of(new MethodParameter(0, "record", ParameterRole.LEGACY_RECORD, "record", Map.class.getName(), true)), impl, legacy));
        MethodParameter legacyParam = new MethodParameter(0, "record", ParameterRole.LEGACY_RECORD, "record", Map.class.getName(), true);
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:legacy",
                TestType.VALIDATION,
                CountingLegacyImpl.class.getName(),
                "validate",
                Phase.PRE_AMENDMENT,
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "test",
                true,
                List.of(new BoundMethodParameter(legacyParam, "record", null, true, "Legacy compatibility binding")),
                List.of());

        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r3", Map.of("dwc:country", "Greenland"))));

        service.execute(dataset, List.of(binding), discovered);

        // Doubled by the implicit PRE_AMENDMENT -> POST_AMENDMENT re-run (see bindingsForPhase).
        assertThat(impl.invocationCount.get())
                .as("legacy whole-record bindings are never dedup-eligible")
                .isEqualTo(6);
    }

    @Test
    void dedupDisabledRunsEveryRecordEvenWithDuplicateValues() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter(), false);
        CountingImpl impl = new CountingImpl();
        Method validate = CountingImpl.class.getMethod("validate", String.class);
        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("urn:test:validation", null, TestType.VALIDATION, Phase.PRE_AMENDMENT, CountingImpl.class.getName(), "validate", null,
                        List.of(parameter(0, ParameterRole.ACTED_UPON, "dwc:country", String.class)), impl, validate));
        List<ImplementationBinding> bindings = List.of(
                bindingOnField("urn:test:validation", TestType.VALIDATION, CountingImpl.class.getName(), "validate", Phase.PRE_AMENDMENT, "dwc:country"));
        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r2", Map.of("dwc:country", "Greenland")),
                new CanonicalRecord("r3", Map.of("dwc:country", "Greenland"))));

        service.execute(dataset, bindings, discovered);

        // Doubled by the implicit PRE_AMENDMENT -> POST_AMENDMENT re-run (see bindingsForPhase).
        assertThat(impl.invocationCount.get())
                .as("dedup disabled means every record is invoked, even with identical values")
                .isEqualTo(6);
    }

    @Test
    void builtInMeasureCountsReflectAllRecordsNotJustDistinctGroups() throws Exception {
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

        // 4 records all sharing the same dwc:eventDate value ("match"), so the direct validation
        // binding is invoked only once, but the built-in measure must still count all 4 records.
        RecordDataset dataset = new RecordDataset(List.of(
                new CanonicalRecord("r1", Map.of("dwc:eventDate", "match")),
                new CanonicalRecord("r2", Map.of("dwc:eventDate", "match")),
                new CanonicalRecord("r3", Map.of("dwc:eventDate", "match")),
                new CanonicalRecord("r4", Map.of("dwc:eventDate", "match"))));

        var responses = service.execute(dataset, bindings, discovered);

        assertThat(responses.stream()
                .filter(response -> response.testId().equals("urn:test:measure") && response.phase() == Phase.PRE_AMENDMENT)
                .toList())
                .extracting(Response::responseResult, Response::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("4", "4/4 records matched COMPLIANT for VALIDATION_BASISOFRECORD_NOTEMPTY (100.0%)"));
    }

    private static List<Object> stripTimestamps(List<Response> responses) {
        return responses.stream()
                .map(r -> List.of(
                        r.recordId(), r.testId(), r.testType(), r.phase(),
                        String.valueOf(r.status()), String.valueOf(r.responseStatus()), String.valueOf(r.responseResult()),
                        String.valueOf(r.comment()), r.amendments()))
                .map(Object.class::cast)
                .toList();
    }

    private static MethodParameter parameter(int index, ParameterRole role, String source, Class<?> type) {
        return new MethodParameter(index, "p" + index, role, source, type.getName(), true);
    }

    private static ImplementationBinding binding(String testId, TestType testType, String method, Phase phase) {
        return binding(testId, testType, Impl.class.getName(), method, phase);
    }

    private static ImplementationBinding binding(String testId, TestType testType, String implementationClass, String method, Phase phase) {
        return bindingOnField(testId, testType, implementationClass, method, phase, "dwc:eventDate");
    }

    private static ImplementationBinding bindingOnField(
            String testId, TestType testType, String implementationClass, String method, Phase phase, String field) {
        MethodParameter parameter = parameter(0, ParameterRole.ACTED_UPON, field, String.class);
        BoundMethodParameter bound = new BoundMethodParameter(parameter, field, null, true, "Mapped");
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

    static class CountingImpl {
        final AtomicInteger invocationCount = new AtomicInteger();

        public StubDQResponse validate(String country) {
            invocationCount.incrementAndGet();
            return new StubDQResponse(
                    "RUN_HAS_RESULT",
                    "Greenland".equals(country) ? "COMPLIANT" : "NOT_COMPLIANT",
                    country,
                    Map.of());
        }
    }

    static class CountingLegacyImpl {
        final AtomicInteger invocationCount = new AtomicInteger();

        public StubDQResponse validate(Map<String, String> record) {
            invocationCount.incrementAndGet();
            return new StubDQResponse("RUN_HAS_RESULT", "COMPLIANT", "ok", Map.of());
        }
    }

    static class CountryToCodeImpl {
        public StubDQResponse toCode(String country) {
            String code = "Greenland".equals(country) ? "GL" : "XX";
            return new StubDQResponse("AMENDED", Map.of("dwc:countryCode", code), "coded to " + code, Map.of("dwc:countryCode", code));
        }
    }

    static class CodeCounterImpl {
        final AtomicInteger invocationCount = new AtomicInteger();

        public StubDQResponse fromCode(String countryCode) {
            invocationCount.incrementAndGet();
            return new StubDQResponse("AMENDED", Map.of("dwc:localityNote", "coded:" + countryCode), "noted", Map.of("dwc:localityNote", "coded:" + countryCode));
        }
    }

    static class EchoLocalityAndCodeImpl {
        public StubDQResponse echo(String locality, String countryCode) {
            String echoed = locality + ":" + countryCode;
            return new StubDQResponse("RUN_HAS_RESULT", echoed, echoed, Map.of());
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
