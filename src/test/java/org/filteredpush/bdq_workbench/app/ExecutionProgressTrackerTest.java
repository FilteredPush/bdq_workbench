package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.OutcomeStatus;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.junit.jupiter.api.Test;

class ExecutionProgressTrackerTest {

    @Test
    void tracksGenuineConcurrencyAcrossTaskStartAndResponse() {
        ExecutionProgressTracker tracker = new ExecutionProgressTracker();
        tracker.onPhaseStarted(Phase.PRE_AMENDMENT, 3);
        assertThat(tracker.snapshot().running()).isZero();

        tracker.onTaskStarted(Phase.PRE_AMENDMENT);
        tracker.onTaskStarted(Phase.PRE_AMENDMENT);
        assertThat(tracker.snapshot().running()).isEqualTo(2);
        assertThat(tracker.snapshot().queued()).isEqualTo(1);

        tracker.onResponse(Phase.PRE_AMENDMENT, response(), 1, 3);
        assertThat(tracker.snapshot().running()).isEqualTo(1);
        assertThat(tracker.snapshot().completed()).isEqualTo(1);

        tracker.onResponse(Phase.PRE_AMENDMENT, response(), 2, 3);
        assertThat(tracker.snapshot().running()).isZero();
    }

    @Test
    void neverGoesNegativeWhenAResponseArrivesWithoutAMatchingTaskStarted() {
        ExecutionProgressTracker tracker = new ExecutionProgressTracker();
        tracker.onPhaseStarted(Phase.PRE_AMENDMENT, 1);

        // Built-in multi-record measures are synthesized synchronously and never call
        // onTaskStarted, so their onResponse must not drive running negative.
        tracker.onResponse(Phase.PRE_AMENDMENT, response(), 1, 1);

        assertThat(tracker.snapshot().running()).isZero();
    }

    private static Response response() {
        return new Response(
                "r1",
                "urn:test:validation",
                TestType.VALIDATION,
                "example.Impl",
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
                Instant.now());
    }
}
