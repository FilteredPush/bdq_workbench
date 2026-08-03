/** ExecutionResultSummary.java
 *
 * Aggregated, GUI/export-friendly view of an {@link ExecutionSummary}: counts by phase,
 * response status, and response result, plus multi-record measure outputs.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.Response;

/**
 * Aggregated result summary for UI and exports.
 *
 * @param phaseCounts number of responses per {@link Phase}
 * @param responseStatusCounts number of responses per {@code responseStatus} value
 * @param responseResultCounts number of responses per {@code responseResult} value, restricted
 *     to result values that look like an all-caps keyword (see
 *     {@link #isSummaryResultKeyword(String)}) — filtering out free-text/measure values that
 *     would otherwise clutter a result-count display
 * @param multiRecordMeasureOutputs multi-record MEASURE responses, keyed by test ID then phase,
 *     as produced by {@link ExecutionSummary#multiRecordMeasureResponsesByTestAndPhase()}
 */
public record ExecutionResultSummary(
        Map<Phase, Long> phaseCounts,
        Map<String, Long> responseStatusCounts,
        Map<String, Long> responseResultCounts,
        Map<String, Map<Phase, Response>> multiRecordMeasureOutputs) {

    /**
     * Builds a result summary by aggregating an {@link ExecutionSummary}'s responses.
     *
     * @param summary the execution summary to summarize
     * @return the aggregated result summary
     */
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

    /**
     * Checks whether a response result value looks like an all-caps keyword (e.g.
     * {@code COMPLIANT}, {@code NOT_COMPLIANT}) suitable for a result-count summary, as opposed
     * to free-text or numeric measure output.
     *
     * @param value the response result value to check
     * @return {@code true} if {@code value} is non-null and matches {@code [A-Z][A-Z0-9_]*}
     */
    private static boolean isSummaryResultKeyword(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]*");
    }
}
