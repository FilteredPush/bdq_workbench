package org.filteredpush.bdq_workbench.model;

import java.util.Map;

/** Additional execution context used by summary renderers and exports. */
public record ExecutionSummaryMetadata(
        String useCaseId,
        String useCaseLabel,
        String inputFile,
        int darwinCoreTermCount,
        int singleRecordCount,
        Map<String, Long> filledInValueCounts,
        Map<String, Long> amendedValuePairCounts) {

    public static ExecutionSummaryMetadata empty() {
        return new ExecutionSummaryMetadata("", "", "", 0, 0, Map.of(), Map.of());
    }
}
