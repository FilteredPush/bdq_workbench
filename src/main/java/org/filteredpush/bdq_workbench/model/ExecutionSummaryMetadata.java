/** ExecutionSummaryMetadata.java
 *
 * Additional execution context (use case identity, dataset info, and value-change tallies) attached to an ExecutionSummary and used by summary renderers and exports.
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
package org.filteredpush.bdq_workbench.model;

import java.util.Map;

/**
 * Additional execution context used by summary renderers and exports.
 *
 * @param useCaseId the identifier of the use case the run was executed for
 * @param useCaseLabel the human-readable label of the use case the run was executed for
 * @param inputFile the path of the ingested dataset file, or {@code ""} if unknown
 * @param darwinCoreTermCount the number of distinct Darwin Core terms present across the dataset
 * @param singleRecordCount the number of individual records in the dataset
 * @param filledInValueCounts tallies of {@code term=value} pairs produced by amendments that
 *     filled in a previously empty term, keyed as described in
 *     {@link org.filteredpush.bdq_workbench.app.WorkbenchFacade}
 * @param amendedValuePairCounts tallies of {@code term: oldValue -> newValue} transitions
 *     produced by amendments that changed an existing term value
 */
public record ExecutionSummaryMetadata(
        String useCaseId,
        String useCaseLabel,
        String inputFile,
        int darwinCoreTermCount,
        int singleRecordCount,
        Map<String, Long> filledInValueCounts,
        Map<String, Long> amendedValuePairCounts) {

    /**
     * Returns an empty metadata instance, used as the default when no metadata is available.
     *
     * @return metadata with blank identity fields, zero counts, and empty tally maps
     */
    public static ExecutionSummaryMetadata empty() {
        return new ExecutionSummaryMetadata("", "", "", 0, 0, Map.of(), Map.of());
    }
}
