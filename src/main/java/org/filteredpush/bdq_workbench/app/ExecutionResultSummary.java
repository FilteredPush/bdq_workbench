package org.filteredpush.bdq_workbench.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/** Aggregated result summary for UI and exports. */
public record ExecutionResultSummary(
        Map<Phase, Long> phaseCounts,
        Map<String, Long> responseStatusCounts,
        Map<String, Long> responseResultCounts,
        Map<String, Map<Phase, Response>> multiRecordMeasureOutputs) {

    public static ExecutionResultSummary from(ExecutionSummary summary) {
        return new ExecutionResultSummary(
                summary.countsByPhase(),
                summary.countsByResponseStatus(),
                summary.countsByResponseResult().entrySet().stream()
                        .filter(entry -> isSummaryResultKeyword(entry.getKey()))
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> right,
                                LinkedHashMap::new)),
                summary.multiRecordMeasureResponsesByTestAndPhase());
    }

    private static boolean isSummaryResultKeyword(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]*");
    }
}
