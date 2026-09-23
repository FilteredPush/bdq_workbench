package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.CardLayout;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.filteredpush.bdq_workbench.model.BuiltInMeasureSpec;
import org.filteredpush.bdq_workbench.model.ExecutionPlan;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ExecutionSummaryMetadata;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
import org.filteredpush.bdq_workbench.model.PreparedRun;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Policy;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordFilterSummary;
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
    void preflightSummaryCountsOnlyRunnableBindings() throws Exception {
        PreparedRun preparedRun = new PreparedRun(
                new AppConfig(Path.of("usecase.xml"), List.of(), Path.of("dataset.zip"), "uc1", List.of("org.filteredpush"), 1, true),
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01")))),
                new ExecutionPlan(
                        new UseCase("uc1", "Use Case", "policy:1"),
                        new Policy("policy:1", List.of("urn:test:runnable", "urn:test:non-runnable")),
                        List.of(
                                new TestDefinition("urn:test:runnable", "Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                                new TestDefinition("urn:test:non-runnable", "Non Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of())),
                        List.of()),
                List.of(),
                new TestBindingResult(
                        List.of(
                                new ImplementationBinding(
                                        "urn:test:runnable",
                                        TestType.VALIDATION,
                                        "example.Impl",
                                        "run",
                                        Phase.PRE_AMENDMENT,
                                        Map.of(),
                                        BindingStatus.BOUND,
                                        ParameterizationCapability.DEFAULT_ONLY,
                                        "selected",
                                        true,
                                        List.of(),
                                        List.of("BOUND: all parameters compatible")),
                                new ImplementationBinding(
                                        "urn:test:non-runnable",
                                        TestType.VALIDATION,
                                        "example.Impl",
                                        "skip",
                                        Phase.PRE_AMENDMENT,
                                        Map.of(),
                                        BindingStatus.UNBOUND,
                                        ParameterizationCapability.DEFAULT_ONLY,
                                        "selected",
                                        true,
                                        List.of(),
                                        List.of("Missing parameter value for bdq:sourceAuthority"))),
                        List.of(new TestDefinition("urn:test:non-runnable", "Non Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of())),
                        List.of(
                                new BindingReview(
                                        new TestDefinition("urn:test:runnable", "Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                                        ImplementationStatus.FOUND,
                                        BindingStatus.BOUND,
                                        ParameterizationCapability.DEFAULT_ONLY,
                                        "example.Impl#run()",
                                        Map.of(),
                                        true,
                                        List.of("BOUND: all parameters compatible")),
                                new BindingReview(
                                        new TestDefinition("urn:test:non-runnable", "Non Runnable", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                                        ImplementationStatus.FOUND,
                                        BindingStatus.UNBOUND,
                                        ParameterizationCapability.DEFAULT_ONLY,
                                        "example.Impl#skip()",
                                        Map.of(),
                                        true,
                                        List.of("Missing parameter value for bdq:sourceAuthority")))),
                RecordFilterSummary.unfiltered(new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:eventDate", "2025-01-01"))))));

        Class<?> preflightStateClass = Class.forName("org.filteredpush.bdq_workbench.app.BdqWorkbenchGui$PreflightState");
        java.lang.reflect.Constructor<?> constructor = preflightStateClass.getDeclaredConstructor(PreparedRun.class);
        constructor.setAccessible(true);
        Object preflightState = constructor.newInstance(preparedRun);
        Method renderPreflightMessage = BdqWorkbenchGui.class.getDeclaredMethod("renderPreflightMessage", preflightStateClass);
        renderPreflightMessage.setAccessible(true);

        String summary = (String) renderPreflightMessage.invoke(null, preflightState);

        assertThat(summary).contains("Runnable mapped tests: 1");
        assertThat(summary).contains("Mapped but not runnable");
        assertThat(summary).contains("right-click a single test row");
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
                        2,
                        1,
                        Map.of(),
                        Map.of(),
                        Map.of("dwc:countryCode=RU", 1L),
                        Map.of("dwc:countryCode: SU -> RU", 1L)));

        String text = (String) renderSummary.invoke(null, summary);

        assertThat(text).contains("Results summary");
        assertThat(text).contains("Use case: urn:usecase:1 (Use Case One)\n");
        assertThat(text).contains("Input file: /tmp/input.csv\n");
        assertThat(text).contains("Darwin Core terms present in input file: 2\n");
        assertThat(text).contains("SingleRecords in input file: 1\n");
        assertThat(text).contains("Darwin Core terms selected for execution: 2\n");
        assertThat(text).contains("SingleRecords selected for execution: 1\n");
        assertThat(text).contains("Record filters:\n - none\n");
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

    @Test
    void bindingReviewTableModelSortsMeasuresAheadOfSingleRecordTestsAfterRun() {
        BindingReviewTableModel model = new BindingReviewTableModel(List.of(
                new BindingReview(
                        new TestDefinition("urn:test:validation", "Zebra validation", TestType.VALIDATION, Phase.PRE_AMENDMENT, Map.of()),
                        ImplementationStatus.FOUND,
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "example#validation",
                        Map.of(),
                        true,
                        List.of()),
                new BindingReview(
                        new TestDefinition("urn:test:measure-without", "Bravo measure", TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of()),
                        ImplementationStatus.FOUND,
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "built-in",
                        Map.of(),
                        true,
                        List.of()),
                new BindingReview(
                        new TestDefinition("urn:test:measure-with", "Alpha measure", TestType.MEASURE, Phase.PRE_AMENDMENT, Map.of()),
                        ImplementationStatus.FOUND,
                        BindingStatus.BOUND,
                        ParameterizationCapability.DEFAULT_ONLY,
                        "built-in",
                        Map.of(),
                        true,
                        List.of())));

        model.applyExecutionOutputs(Map.of(
                "urn:test:measure-with",
                new BindingReviewTableModel.PhaseExecutionOutput("1 (50.0%)", "")));

        assertThat(model.reviewAt(0).test().label()).isEqualTo("Alpha measure");
        assertThat(model.reviewAt(1).test().label()).isEqualTo("Bravo measure");
        assertThat(model.reviewAt(2).test().label()).isEqualTo("Zebra validation");
    }

    @Test
    void stageOverviewIncludesFilterStageCounts() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "renderStageOverview",
                PreparedRun.class,
                Phase.class,
                boolean.class,
                boolean.class,
                boolean.class);
        helper.setAccessible(true);
        PreparedRun preparedRun = new PreparedRun(
                null,
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))),
                new ExecutionPlan(new UseCase("urn:usecase", "Use case", "urn:policy"), new Policy("urn:policy", List.of()), List.of(), List.of()),
                List.of(),
                new TestBindingResult(List.of(), List.of(), List.of()),
                new RecordFilterSummary(
                        new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))),
                        3,
                        1,
                        2,
                        2,
                        1,
                        Map.of("dwc:country", List.of("Canada")),
                        List.of("Record filter field country resolved to input field dwc:country")));

        String overview = (String) helper.invoke(null, preparedRun, null, false, false, false);

        assertThat(overview).contains("Workflow progress: 5/9 stages completed");
        assertThat(overview).contains("[completed] Load dataset - 3 records loaded");
        assertThat(overview).contains("[completed] Apply record filters - 1 kept, 2 excluded");
        assertThat(overview).contains("[pending] PRE_AMENDMENT - phase not started");
        assertThat(overview).contains("[pending] Export reports - reports not written yet");
    }

    @Test
    void stageOverviewShowsOverallWorkflowProgressWhileRunning() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "renderStageOverview",
                PreparedRun.class,
                Phase.class,
                boolean.class,
                boolean.class,
                boolean.class);
        helper.setAccessible(true);
        PreparedRun preparedRun = new PreparedRun(
                null,
                new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))),
                new ExecutionPlan(new UseCase("urn:usecase", "Use case", "urn:policy"), new Policy("urn:policy", List.of()), List.of(), List.of()),
                List.of(),
                new TestBindingResult(List.of(), List.of(), List.of()),
                RecordFilterSummary.unfiltered(new RecordDataset(List.of(
                        new CanonicalRecord("r1", Map.of("dwc:country", "Canada"))))));

        String overview = (String) helper.invoke(null, preparedRun, Phase.AMENDMENT, false, false, false);

        assertThat(overview).contains("Workflow progress: 6/9 stages completed");
        assertThat(overview).contains("Current stage: AMENDMENT (stage 7/9)");
        assertThat(overview).contains("[completed] PRE_AMENDMENT - phase complete");
        assertThat(overview).contains("[running] AMENDMENT - phase in progress");
        assertThat(overview).contains("[pending] POST_AMENDMENT - phase not started");
    }

    @Test
    void stageOverviewShowsExportStageWhileReportsAreWriting() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
		"renderStageOverview",
		PreparedRun.class,
		Phase.class,
		boolean.class,
		boolean.class,
		boolean.class);
        helper.setAccessible(true);
        PreparedRun preparedRun = new PreparedRun(
		null,
		new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))),
		new ExecutionPlan(new UseCase("urn:usecase", "Use case", "urn:policy"), new Policy("urn:policy", List.of()), List.of(), List.of()),
		List.of(),
		new TestBindingResult(List.of(), List.of(), List.of()),
		RecordFilterSummary.unfiltered(new RecordDataset(List.of(
				new CanonicalRecord("r1", Map.of("dwc:country", "Canada"))))));

        String overview = (String) helper.invoke(null, preparedRun, null, true, false, false);

        assertThat(overview).contains("Workflow progress: 8/9 stages completed");
        assertThat(overview).contains("Current stage: Export reports (stage 9/9)");
        assertThat(overview).contains("[completed] POST_AMENDMENT - phase complete");
        assertThat(overview).contains("[running] Export reports - reports in progress");
    }

    @Test
    void setupFilterSummaryEncouragesDatasetInformedDialog() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
		"renderRecordFilterSelectionSummary",
		String.class);
        helper.setAccessible(true);

        String empty = (String) helper.invoke(null, "");
        String populated = (String) helper.invoke(null, "dwc:country=Canada; genus=Abies|Pinus");

        assertThat(empty).contains("No record filters configured.");
        assertThat(empty).contains("Build Record Filters...");
        assertThat(populated).contains("Configured record filters");
        assertThat(populated).contains(" - dwc:country = Canada");
        assertThat(populated).contains(" - genus = Abies | Pinus");
    }

    @Test
    void recordFilterProfileSuggestionsSummarizeCommonDatasetValues() throws Exception {
        Method profileHelper = BdqWorkbenchGui.class.getDeclaredMethod("profileRecordFilters", RecordDataset.class);
        profileHelper.setAccessible(true);
        Object profile = profileHelper.invoke(null, new RecordDataset(List.of(
		new CanonicalRecord("r1", Map.of("dwc:country", "Canada", "dwc:genus", "Abies")),
		new CanonicalRecord("r2", Map.of("dwc:country", "Canada", "dwc:genus", "Pinus")),
		new CanonicalRecord("r3", Map.of("dwc:country", "Mexico", "dwc:genus", "Abies")))));
        Method suggestionHelper = BdqWorkbenchGui.class.getDeclaredMethod(
		"renderRecordFilterValueSuggestions",
		profile.getClass(),
		String.class,
		String.class);
        suggestionHelper.setAccessible(true);

        String suggestions = (String) suggestionHelper.invoke(null, profile, "dwc:country", null);

        assertThat(suggestions).contains("Common values for dwc:country");
        assertThat(suggestions).contains(" - Canada (2)");
        assertThat(suggestions).contains(" - Mexico (1)");
    }

	@Test
	void recordFilterProfileSuggestionsShowTwentyValuesThenSummarizeRemainder() throws Exception {
		Method profileHelper = BdqWorkbenchGui.class.getDeclaredMethod("profileRecordFilters", RecordDataset.class);
		profileHelper.setAccessible(true);
		List<CanonicalRecord> records = new ArrayList<>();
		for (int i = 1; i <= 21; i++) {
			records.add(new CanonicalRecord("r" + i, Map.of("dwc:country", "v" + i)));
		}
		Object profile = profileHelper.invoke(null, new RecordDataset(records));
		Method suggestionHelper = BdqWorkbenchGui.class.getDeclaredMethod(
				"renderRecordFilterValueSuggestions",
				profile.getClass(),
				String.class,
				String.class);
		suggestionHelper.setAccessible(true);

		String suggestions = (String) suggestionHelper.invoke(null, profile, "dwc:country", null);

		assertThat(suggestions).contains(" - v1 (1)");
		assertThat(suggestions).contains(" - v20 (1)");
		assertThat(suggestions).doesNotContain(" - v21 (1)");
		assertThat(suggestions).contains("... and 1 more distinct value(s)");
	}

    @Test
    void recordFilterSuggestionsWarnWhenSavedFieldIsMissingFromCurrentDataset() throws Exception {
        Method profileHelper = BdqWorkbenchGui.class.getDeclaredMethod("profileRecordFilters", RecordDataset.class);
        profileHelper.setAccessible(true);
        Object profile = profileHelper.invoke(null, new RecordDataset(List.of(
		new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))));
        Method suggestionHelper = BdqWorkbenchGui.class.getDeclaredMethod(
		"renderRecordFilterValueSuggestions",
		profile.getClass(),
		String.class,
		String.class);
        suggestionHelper.setAccessible(true);

        String suggestions = (String) suggestionHelper.invoke(
                null,
                profile,
                "",
                "Saved filter field \"dwc:genus\" is not present in the currently selected dataset.\nChoose a dataset field or remove this row.");

        assertThat(suggestions).contains("dwc:genus");
        assertThat(suggestions).contains("Choose a dataset field or remove this row.");
    }

    @Test
    void buildConfigUsesGuiDedupSelection() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "buildConfig",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                CachedResourceResolver.class,
                AppConfig.class);
        helper.setAccessible(true);
        Path base = Path.of("src", "test", "resources", "integration");
        AppConfig defaults = new AppConfig(
                base.resolve("bdquc.xml"),
                List.of(base.resolve("bdqtest.ttl")),
                base.resolve("dataset.zip"),
                "uc1",
                List.of("org.filteredpush"),
                4,
                true);
        CachedResourceResolver resolver = new CachedResourceResolver();

        AppConfig config = (AppConfig) helper.invoke(
                null,
                base.resolve("dataset.zip").toString(),
                "uc1",
                "",
                "",
                base.resolve("bdquc.xml").toString(),
                base.resolve("bdqtest.ttl").toString(),
                "",
                base.resolve("bdqtest.ttl").toString(),
                "org.filteredpush",
                "2",
                false,
                resolver,
                defaults);

        assertThat(config.dedupEnabled()).isFalse();
    }

    @Test
    void preferredDefaultUseCaseIdFallsBackToSpatialTemporalPatterns() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "preferredDefaultUseCaseId",
                List.class,
                String.class);
        helper.setAccessible(true);

        String selected = (String) helper.invoke(
                null,
                List.of(
                        new UseCase("urn:usecase:1", "Taxonomic Completeness", "urn:policy:1"),
                        new UseCase("urn:usecase:2", "Spatial-Temporal Patterns", "urn:policy:2"),
                        new UseCase("urn:usecase:3", "Georeference Completeness", "urn:policy:3")),
                "");

        assertThat(selected).isEqualTo("urn:usecase:2");
    }

    @Test
    void preferredDefaultUseCaseIdKeepsExplicitConfiguredDefault() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "preferredDefaultUseCaseId",
                List.class,
                String.class);
        helper.setAccessible(true);

        String selected = (String) helper.invoke(
                null,
                List.of(
                        new UseCase("urn:usecase:1", "Taxonomic Completeness", "urn:policy:1"),
                        new UseCase("urn:usecase:2", "Spatial-Temporal Patterns", "urn:policy:2")),
                "urn:usecase:1");

        assertThat(selected).isEqualTo("urn:usecase:1");
    }

    @Test
    void longestSelectableTermUsesLongestNonBlankFieldForStableComboSizing() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod("longestSelectableTerm", List.class);
        helper.setAccessible(true);

        String selected = (String) helper.invoke(null, List.of("", "dwc:genus", "dwc:collectionCode", "dwc:country"));

        assertThat(selected).isEqualTo("dwc:collectionCode");
    }

    @Test
    void showWorkflowVisualizationTogglesWholeMonitorContentCardAndButtonLabel() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
                "showWorkflowVisualization",
                CardLayout.class,
                JPanel.class,
                JButton.class,
                boolean[].class,
                boolean.class);
        helper.setAccessible(true);
        CardLayout cards = new CardLayout();
        JPanel contentPanel = new JPanel(cards);
        contentPanel.add(new JPanel(), "results");
        contentPanel.add(new JPanel(), "workflow");
        JButton toggleButton = new JButton();
        boolean[] showingWorkflow = new boolean[] {false};

        helper.invoke(null, cards, contentPanel, toggleButton, showingWorkflow, true);
        assertThat(showingWorkflow[0]).isTrue();
        assertThat(toggleButton.getText()).isEqualTo("Hide Workflow Visualization");

        helper.invoke(null, cards, contentPanel, toggleButton, showingWorkflow, false);
        assertThat(showingWorkflow[0]).isFalse();
        assertThat(toggleButton.getText()).isEqualTo("Show Workflow Visualization");
    }

    @Test
    void recordFilterSuggestionsWarnWhenSavedAliasIsAmbiguous() throws Exception {
        Method profileHelper = BdqWorkbenchGui.class.getDeclaredMethod("profileRecordFilters", RecordDataset.class);
        profileHelper.setAccessible(true);
        Object profile = profileHelper.invoke(null, new RecordDataset(List.of(
		new CanonicalRecord("r1", Map.of("country", "Canada", "dwc:country", "Canada")))));
        Method suggestionHelper = BdqWorkbenchGui.class.getDeclaredMethod(
		"renderRecordFilterValueSuggestions",
		profile.getClass(),
		String.class,
		String.class);
        suggestionHelper.setAccessible(true);

        String suggestions = (String) suggestionHelper.invoke(
		null,
		profile,
		"",
		"Saved filter field \"country\" matches multiple fields in the currently selected dataset.\nChoose an exact dataset field or remove this row.");

        assertThat(suggestions).contains("matches multiple fields");
        assertThat(suggestions).contains("Choose an exact dataset field");
    }

    @Test
    void applyParameterEditsPreservesFilterSummary() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod(
		"applyParameterEdits",
		PreparedRun.class,
		BindingReviewTableModel.class);
        helper.setAccessible(true);
        PreparedRun preparedRun = new PreparedRun(
		null,
		new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))),
		new ExecutionPlan(new UseCase("urn:usecase", "Use case", "urn:policy"), new Policy("urn:policy", List.of()), List.of(), List.of()),
		List.of(),
		new TestBindingResult(List.of(), List.of(), List.of()),
		new RecordFilterSummary(
				new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("dwc:country", "Canada")))),
				3,
				1,
				2,
				2,
				1,
				Map.of("dwc:country", List.of("Canada")),
				List.of("Record filter field country resolved to input field dwc:country")));
        BindingReviewTableModel model = new BindingReviewTableModel(List.of());

        PreparedRun rebound = (PreparedRun) helper.invoke(null, preparedRun, model);

        assertThat(rebound.filterSummary()).isEqualTo(preparedRun.filterSummary());
    }

    @Test
    void summarizeCountMeasuresBuildsPreAndPostVisualizationData() throws Exception {
        Method helper = BdqWorkbenchGui.class.getDeclaredMethod("summarizeCountMeasures", ExecutionSummary.class);
        helper.setAccessible(true);
        ExecutionSummary summary = new ExecutionSummary(List.of(
                new Response(
                        "MULTIRECORD",
                        "urn:test:count",
                        TestType.MEASURE,
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                        BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        Phase.PRE_AMENDMENT,
                        Map.of(
                                BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.COUNT.name(),
                                BuiltInMeasureSpec.MEASURE_LABEL_KEY, "Count compliant basisOfRecord",
                                BuiltInMeasureSpec.MATCHING_COUNT_KEY, "1",
                                BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "4",
                                BuiltInMeasureSpec.PERCENTAGE_KEY, "25.0"),
                        OutcomeStatus.PASSED,
                        "RUN_HAS_RESULT",
                        "1",
                        "1",
                        "1",
                        Map.of(),
                        Instant.now(),
                        Instant.now()),
                new Response(
                        "MULTIRECORD",
                        "urn:test:count",
                        TestType.MEASURE,
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                        BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        Phase.POST_AMENDMENT,
                        Map.of(
                                BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.COUNT.name(),
                                BuiltInMeasureSpec.MEASURE_LABEL_KEY, "Count compliant basisOfRecord",
                                BuiltInMeasureSpec.MATCHING_COUNT_KEY, "3",
                                BuiltInMeasureSpec.TOTAL_RECORDS_KEY, "4",
                                BuiltInMeasureSpec.PERCENTAGE_KEY, "75.0"),
                        OutcomeStatus.PASSED,
                        "RUN_HAS_RESULT",
                        "3",
                        "3",
                        "3",
                        Map.of(),
                        Instant.now(),
                        Instant.now()),
                new Response(
                        "MULTIRECORD",
                        "urn:test:qa",
                        TestType.MEASURE,
                        BuiltInMeasureSpec.IMPLEMENTATION_CLASS,
                        BuiltInMeasureSpec.IMPLEMENTATION_METHOD,
                        Phase.PRE_AMENDMENT,
                        Map.of(
                                BuiltInMeasureSpec.KIND_KEY, BuiltInMeasureSpec.MeasureKind.QA.name(),
                                BuiltInMeasureSpec.MEASURE_LABEL_KEY, "QA summary"),
                        OutcomeStatus.PASSED,
                        "RUN_HAS_RESULT",
                        "COMPLIANT",
                        "COMPLIANT",
                        "COMPLIANT",
                        Map.of(),
                        Instant.now(),
                        Instant.now())));

        @SuppressWarnings("unchecked")
        List<Object> measures = (List<Object>) helper.invoke(null, summary);

        assertThat(measures).hasSize(1);
        Object measure = measures.get(0);
        Method label = measure.getClass().getDeclaredMethod("label");
        Method prePercent = measure.getClass().getDeclaredMethod("prePercent");
        Method preText = measure.getClass().getDeclaredMethod("preText");
        Method postPercent = measure.getClass().getDeclaredMethod("postPercent");
        Method postText = measure.getClass().getDeclaredMethod("postText");
        label.setAccessible(true);
        prePercent.setAccessible(true);
        preText.setAccessible(true);
        postPercent.setAccessible(true);
        postText.setAccessible(true);

        assertThat(label.invoke(measure)).isEqualTo("Count compliant basisOfRecord");
        assertThat(prePercent.invoke(measure)).isEqualTo(25);
        assertThat(preText.invoke(measure)).isEqualTo("1/4 (25.0%)");
        assertThat(postPercent.invoke(measure)).isEqualTo(75);
        assertThat(postText.invoke(measure)).isEqualTo("3/4 (75.0%)");
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
