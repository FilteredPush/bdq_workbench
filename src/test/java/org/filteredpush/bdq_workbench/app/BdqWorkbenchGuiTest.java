package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.BindingReview;
import org.filteredpush.bdq_workbench.model.BindingStatus;
import org.filteredpush.bdq_workbench.model.ImplementationStatus;
import org.filteredpush.bdq_workbench.model.ParameterizationCapability;
import org.filteredpush.bdq_workbench.model.Phase;
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
}
