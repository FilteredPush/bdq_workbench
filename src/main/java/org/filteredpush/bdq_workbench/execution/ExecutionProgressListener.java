package org.filteredpush.bdq_workbench.execution;

import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/** Listener for execution progress updates suitable for UI reporting. */
public interface ExecutionProgressListener {
    default void onPhaseStarted(Phase phase, int total) {
    }

    default void onResponse(Phase phase, Response response, int completed, int total) {
    }

    default void onPhaseCompleted(Phase phase, int completed, int total) {
    }
}
