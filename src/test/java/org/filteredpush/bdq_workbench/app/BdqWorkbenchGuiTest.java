package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.execution.ReflectionExecutionAdapter;
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
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

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
        assertThat(traceText).contains("Normalized response: PASSED / RUN_HAS_RESULT / COMPLIANT");
    }
}
