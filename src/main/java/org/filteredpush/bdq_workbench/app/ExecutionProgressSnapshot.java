package org.filteredpush.bdq_workbench.app;

import java.util.Map;
import org.filteredpush.bdq_workbench.model.Phase;

/** Immutable progress state for UI updates. */
public record ExecutionProgressSnapshot(
        Phase phase,
        int queued,
        int running,
        int completed,
        int total,
        Map<String, Long> statusCounts,
        Map<String, Long> resultCounts) {
}
