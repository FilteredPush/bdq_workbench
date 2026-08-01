package org.filteredpush.bdq_workbench.app;

import java.util.Map;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;

/** Aggregated result summary for UI and exports. */
public record ExecutionResultSummary(
        Map<Phase, Long> phaseCounts,
        Map<String, Long> responseStatusCounts,
        Map<String, Long> responseResultCounts) {

    public static ExecutionResultSummary from(ExecutionSummary summary) {
        return new ExecutionResultSummary(
                summary.countsByPhase(),
                summary.countsByResponseStatus(),
                summary.countsByResponseResult());
    }
}
