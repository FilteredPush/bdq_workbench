package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Policy;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.filteredpush.bdq_workbench.test_discovery.TestBindingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BdqWorkbenchGuiTest {

    @Test
    void cacheNameIncludesSourceFileName() throws Exception {
        Method method = BdqWorkbenchGui.class.getDeclaredMethod("cacheNameFor", String.class);
        method.setAccessible(true);

        String name = (String) method.invoke(null, "https://bdq.tdwg.org/draft/dist/bdquc.xml");

        assertThat(name).startsWith("bdquc-cached-").endsWith(".xml");
    }

    @Test
    void bindingReviewTableModelExposesEditableParameterState() {
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(new BindingReview(
                new TestDefinition("urn:test", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "example#method",
                Map.of("bdq:limit", "10"),
                false,
                List.of())));

        assertThat(model.getValueAt(0, 5)).isEqualTo("example#method");
        model.setValueAt("bdq:limit=25; bdq:flag=true", 0, 7);
        assertThat(model.editedParametersFor("urn:test"))
                .containsEntry("bdq:limit", "25")
                .containsEntry("bdq:flag", "true");
    }

    @Test
    void bindingReviewTableModelExposesUnderlyingReview() {
        BindingReview review = new BindingReview(
                new TestDefinition("urn:test", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "example#method",
                Map.of(),
                true,
                List.of("BOUND: all parameters compatible"));
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(review));

        assertThat(model.reviewAt(0)).isEqualTo(review);
    }

    @Test
    void bindingReviewTableModelTracksDefaultSelectionInSettings() {
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(new BindingReview(
                new TestDefinition("urn:test", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of("bdq:limit", "10")),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "example#method",
                Map.of("bdq:limit", "10"),
                false,
                List.of())));

        model.applyParameterSettings(Map.of(
                "urn:test",
                new BindingReviewTableModel.ParameterSettings(true, Map.of())));

        assertThat(model.settingsFor("urn:test").useDefaults()).isTrue();
        assertThat(model.settingsFor("urn:test").parameters()).isEmpty();
    }

    @Test
    void bindingReviewTableModelHidesParameterControlsForDefaultOnlyTests() {
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(new BindingReview(
                new TestDefinition("urn:test", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "example#method",
                Map.of(),
                true,
                List.of())));

        assertThat(model.supportsParameterEditing(0)).isFalse();
        assertThat(model.isCellEditable(0, 6)).isFalse();
        assertThat(model.isCellEditable(0, 7)).isFalse();
        assertThat(model.getValueAt(0, 6)).isNull();
        assertThat(model.getValueAt(0, 7)).isEqualTo("");
    }

    @Test
    void progressTrackerBuildsSnapshotCounters() {
        ExecutionProgressTracker tracker = new ExecutionProgressTracker();
        tracker.onPhaseStarted(Phase.PRE_AMENDMENT, 2);
        tracker.onResponse(Phase.PRE_AMENDMENT, new org.filteredpush.bdq_workbench.model.Response(
                "r1", "t1", TestType.VALIDATION, "Impl", "m", Phase.PRE_AMENDMENT, Map.of(),
                org.filteredpush.bdq_workbench.model.OutcomeStatus.PASSED, "RUN_HAS_RESULT", "COMPLIANT",
                "ok", "ok", Map.of(), java.time.Instant.now(), java.time.Instant.now()), 1, 2);

        ExecutionProgressSnapshot snapshot = tracker.snapshot();

        assertThat(snapshot.completed()).isEqualTo(1);
        assertThat(snapshot.statusCounts()).containsEntry("RUN_HAS_RESULT", 1L);
        assertThat(snapshot.resultCounts()).containsEntry("COMPLIANT", 1L);
    }

    @Test
    void debugRenderersShowBindingAndExecutionDetails() throws Exception {
        BindingReview review = new BindingReview(
                new TestDefinition("urn:test", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of("bdq:limit", "10")),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "example#method",
                Map.of("bdq:limit", "10"),
                false,
                List.of("BOUND: all parameters compatible"));
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test",
                TestType.VALIDATION,
                "example",
                "method",
                Phase.PRE_AMENDMENT,
                Map.of("bdq:limit", "10"),
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "parameterized",
                false,
                List.of(new BoundMethodParameter(
                        new MethodParameter(0, "eventDate", ParameterRole.ACTED_UPON, "dwc:eventDate", String.class.getName(), true),
                        "dwc:eventDate",
                        null,
                        true,
                        "Mapped to dwc:eventDate")),
                List.of("BOUND: all parameters compatible"));
        Method renderDetails = BdqWorkbenchGui.class.getDeclaredMethod(
                "renderBindingReviewDetails",
                BindingReview.class,
                ImplementationBinding.class);
        renderDetails.setAccessible(true);

        String detailText = (String) renderDetails.invoke(null, review, binding);

        assertThat(detailText).contains("Selected test");
        assertThat(detailText).contains("Resolved parameter bindings");
        assertThat(detailText).contains("dwc:eventDate");

        Method renderTrace = BdqWorkbenchGui.class.getDeclaredMethod(
                "renderExecutionTrace",
                ReflectionExecutionAdapter.ExecutionTrace.class,
                int.class,
                int.class);
        renderTrace.setAccessible(true);

        ReflectionExecutionAdapter.ExecutionTrace trace = new ReflectionExecutionAdapter.ExecutionTrace(
                new Response(
                        "r1",
                        "urn:test",
                        TestType.VALIDATION,
                        "example",
                        "method",
                        Phase.PRE_AMENDMENT,
                        Map.of("bdq:limit", "10"),
                        OutcomeStatus.PASSED,
                        "RUN_HAS_RESULT",
                        "COMPLIANT",
                        "checked",
                        "checked",
                        Map.of(),
                        Instant.now(),
                        Instant.now()),
                List.of(new ReflectionExecutionAdapter.ArgumentTrace(
                        "eventDate",
                        ParameterRole.ACTED_UPON,
                        "dwc:eventDate",
                        "2025-01-01",
                        "2025-01-01",
                        "Mapped to dwc:eventDate")),
                "example.Result",
                "Result[COMPLIANT]");

        String traceText = (String) renderTrace.invoke(null, trace, 1, 3);

        assertThat(traceText).contains("Record 1/3: r1");
        assertThat(traceText).contains("Raw return value: Result[COMPLIANT]");
        assertThat(traceText).contains("Response: RUN_HAS_RESULT / COMPLIANT");
        assertThat(traceText).doesNotContain("Vocabulary response");
        assertThat(traceText).doesNotContain("Execution state:");
    }

    @Test
    void debugRendererShowsStatusOnlyWhenResponseResultIsMissing() throws Exception {
        Method renderTrace = BdqWorkbenchGui.class.getDeclaredMethod(
                "renderExecutionTrace",
                ReflectionExecutionAdapter.ExecutionTrace.class,
                int.class,
                int.class);
        renderTrace.setAccessible(true);

        ReflectionExecutionAdapter.ExecutionTrace trace = new ReflectionExecutionAdapter.ExecutionTrace(
                new Response(
                        "r1",
                        "urn:test",
                        TestType.VALIDATION,
                        "example",
                        "method",
                        Phase.PRE_AMENDMENT,
                        Map.of(),
                        OutcomeStatus.FAILED,
                        "INTERNAL_PREREQUISITES_NOT_MET",
                        null,
                        null,
                        "INTERNAL_PREREQUISITES_NOT_MET",
                        Map.of(),
                        Instant.now(),
                        Instant.now()),
                List.of(),
                "example.Result",
                "{}");

        String traceText = (String) renderTrace.invoke(null, trace, 1, 1);

        assertThat(traceText).contains("Response: INTERNAL_PREREQUISITES_NOT_MET");
        assertThat(traceText).doesNotContain("/ {}");
    }

    @Test
    void resultSummaryUsesReadableMultiLineSections() throws Exception {
        Method renderSummary = BdqWorkbenchGui.class.getDeclaredMethod("renderResultSummary", ExecutionSummary.class);
        renderSummary.setAccessible(true);

        ExecutionSummary summary = new ExecutionSummary(
                List.of(
                        new Response(
                                "r1",
                                "urn:test:validation",
                                TestType.VALIDATION,
                                "example",
                                "validate",
                                Phase.PRE_AMENDMENT,
                                Map.of(),
                                OutcomeStatus.PASSED,
                                "RUN_HAS_RESULT",
                                "COMPLIANT",
                                "ok",
                                "ok",
                                Map.of(),
                                Instant.now(),
                                Instant.now()),
                        new Response(
                                "MULTIRECORD",
                                "urn:test:count",
                                TestType.MEASURE,
                                BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                                BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                                Phase.PRE_AMENDMENT,
                                Map.of(
                                        BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.COUNT.name(),
                                        BuiltInMeasureSpec.MEASURE_LABEL_KEY, "MULTIRECORD_MEASURE_COUNT_COMPLIANT_BASISOFRECORD_NOTEMPTY",
                                        BuiltInMeasureSpec.MATCHING_COUNT_KEY, "1",
                                        BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "2",
                                        BuiltInMeasureSpec.PERCENTAGE_KEY, "50.0"),
                                OutcomeStatus.PASSED,
                                "RUN_HAS_RESULT",
                                "1",
                                "1",
                                "1",
                                Map.of(),
                                Instant.now(),
                                Instant.now())),
                new ExecutionSummaryMetadata(
                        "urn:usecase:1",
                        "Use Case One",
                        "/tmp/input.csv",
                        2,
                        1,
                        Map.of("dwc:countryCode=RU", 1L),
                        Map.of("dwc:countryCode: SU -> RU", 1L)));

        String text = (String) renderSummary.invoke(null, summary);

        assertThat(text).contains("Results summary");
        assertThat(text).contains("Use case: urn:usecase:1 (Use Case One)\n");
        assertThat(text).contains("Input file: /tmp/input.csv\n");
        assertThat(text).contains("Darwin Core terms present in input file: 2\n");
        assertThat(text).contains("SingleRecords in input file: 1\n");
        assertThat(text).contains("By phase:\n - PRE_AMENDMENT: 2\n");
        assertThat(text).contains("By response status:\n - RUN_HAS_RESULT: 2\n");
        assertThat(text).contains("By response result:");
        assertThat(text).contains(" - COMPLIANT: 1");
        assertThat(text).doesNotContain(" - 1: 1");
        assertThat(text).contains("Multi-record COUNT measures:");
        assertThat(text).contains("Pre-amendment: 1/2 (50.0%)");
        assertThat(text).contains("Top filled-in amendment values:");
        assertThat(text).contains("Top amended original -> proposed values:");
        assertThat(text).doesNotContain("Phase counts: {");
        assertThat(text).doesNotContain("Response result counts: {");
    }

    @Test
    void configurableParametersIncludeParameterizedVariantWhenDefaultMethodIsSelected() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "configurableParametersFor",
                PreparedRun.class,
                BindingReview.class,
                ImplementationBinding.class);
        helper.setAccessible(true);

        Method defaultMethod = GuiDummy.class.getMethod("validate", String.class);
        Method parameterizedMethod = GuiDummy.class.getMethod("validateWithParameter", String.class, Integer.class);
        MethodParameter actedUpon = new MethodParameter(
                0, "eventDate", ParameterRole.ACTED_UPON, "dwc:eventDate", String.class.getName(), true);
        MethodParameter parameter = new MethodParameter(
                1, "latestValidDate", ParameterRole.PARAMETER, "bdq:latestValidDate", Integer.class.getName(), true);
        BindingReview review = new BindingReview(
                new TestDefinition("urn:test:param", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "example#validate",
                Map.of(),
                true,
                List.of("Parameterized version available"));
        ImplementationBinding binding = new ImplementationBinding(
                "urn:test:param",
                TestType.VALIDATION,
                GuiDummy.class.getName(),
                "validate",
                Phase.PRE_AMENDMENT,
                Map.of(),
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "default",
                true,
                List.of(new BoundMethodParameter(actedUpon, "dwc:eventDate", null, true, "Mapped")),
                List.of());
        PreparedRun preparedRun = new PreparedRun(
                null,
                new RecordDataset(List.of()),
                new ExecutionPlan(new UseCase("urn:usecase", "Use case", "urn:policy"), new Policy("urn:policy", List.of()), List.of(), List.of()),
                List.of(
                        new DiscoveredImplementation(
                                "urn:test:param",
                                null,
                                TestType.VALIDATION,
                                Phase.PRE_AMENDMENT,
                                GuiDummy.class.getName(),
                                "validate",
                                null,
                                List.of(actedUpon),
                                new GuiDummy(),
                                defaultMethod),
                        new DiscoveredImplementation(
                                "urn:test:param",
                                null,
                                TestType.VALIDATION,
                                Phase.PRE_AMENDMENT,
                                GuiDummy.class.getName(),
                                "validateWithParameter",
                                null,
                                List.of(actedUpon, parameter),
                                new GuiDummy(),
                                parameterizedMethod)),
                new TestBindingResult(List.of(binding), List.of(), List.of(review)));

        @SuppressWarnings("unchecked")
        List<MethodParameter> configurableParameters =
                (List<MethodParameter>) helper.invoke(null, preparedRun, review, binding);

        assertThat(configurableParameters).extracting(MethodParameter::source).containsExactly("bdq:latestValidDate");
    }

    @Test
    void parameterSettingsRoundTripViaJson(@TempDir Path tempDir) throws Exception {
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(new BindingReview(
                new TestDefinition("urn:test", "Test", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of("bdq:limit", "10")),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.BOTH,
                "example#method",
                Map.of("bdq:limit", "10"),
                false,
                List.of())));
        Path file = tempDir.resolve("settings.json");

        Method saveMethod = BdqWorkbenchGui.class.getDeclaredMethod(
                "writeParameterSettings",
                Path.class,
                Map.class);
        saveMethod.setAccessible(true);
        saveMethod.invoke(null, file, model.parameterSettings());

        Method loadMethod = BdqWorkbenchGui.class.getDeclaredMethod("readParameterSettings", Path.class);
        loadMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, BindingReviewTableModel.ParameterSettings> loaded =
                (Map<String, BindingReviewTableModel.ParameterSettings>) loadMethod.invoke(null, file);

        assertThat(loaded).containsKey("urn:test");
        assertThat(loaded.get("urn:test").parameters()).containsEntry("bdq:limit", "10");
    }

    @Test
    void bindingReviewTableModelCanShowMeasureExecutionOutput() {
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(new BindingReview(
                new TestDefinition("urn:test:measure", "MULTIRECORD_MEASURE_COUNT_COMPLIANT_BASISOFRECORD_NOTEMPTY", TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of()),
                ImplementationStatus.FOUND,
                BindingStatus.BOUND,
                ParameterizationCapability.DEFAULT_ONLY,
                "built-in",
                Map.of(),
                true,
                List.of("Built-in multi-record COUNT measure"))));

        model.applyExecutionOutputs(Map.of(
                "urn:test:measure",
                new BindingReviewTableModel.PhaseExecutionOutput("1 (50.0%)", "2 (100.0%)")));

        assertThat(model.getValueAt(0, 8))
                .isEqualTo("1 (50.0%)");
        assertThat(model.getValueAt(0, 9))
                .isEqualTo("2 (100.0%)");
    }

    static class GuiDummy {
        public boolean validate(String eventDate) {
            return eventDate != null;
        }

        public boolean validateWithParameter(String eventDate, Integer latestValidDate) {
            return eventDate != null && latestValidDate != null;
        }
    }
}
