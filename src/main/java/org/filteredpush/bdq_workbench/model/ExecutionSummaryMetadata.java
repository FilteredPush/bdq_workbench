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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Additional execution context used by summary renderers and exports.
 *
 * @param useCaseId the identifier of the use case the run was executed for
 * @param useCaseLabel the human-readable label of the use case the run was executed for
 * @param inputFile the path of the ingested dataset file, or {@code ""} if unknown
 * @param inputDarwinCoreTermCount the number of distinct Darwin Core terms present in the ingested
 *     dataset before filtering
 * @param inputSingleRecordCount the number of records in the ingested dataset before filtering
 * @param filteredDarwinCoreTermCount the number of distinct Darwin Core terms remaining in the
 *     execution subset after filtering
 * @param filteredSingleRecordCount the number of records selected for execution after filtering
 * @param recordFilters active record filters keyed by resolved dataset field name
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
        int inputDarwinCoreTermCount,
        int inputSingleRecordCount,
        int filteredDarwinCoreTermCount,
        int filteredSingleRecordCount,
        Map<String, List<String>> recordFilters,
        Map<String, Long> filledInValueCounts,
        Map<String, Long> amendedValuePairCounts) {

	/**
	 * Creates metadata with matching input and filtered counts and no record filters.
	 *
	 * @param useCaseId the identifier of the use case the run was executed for
	 * @param useCaseLabel the use case label
	 * @param inputFile the path of the ingested dataset file
	 * @param darwinCoreTermCount the number of distinct Darwin Core terms in the dataset
	 * @param singleRecordCount the number of records in the dataset
	 * @param filledInValueCounts amendment tallies for filled-in values
	 * @param amendedValuePairCounts amendment tallies for changed values
	 */
	public ExecutionSummaryMetadata(
			String useCaseId,
			String useCaseLabel,
			String inputFile,
			int darwinCoreTermCount,
			int singleRecordCount,
			Map<String, Long> filledInValueCounts,
			Map<String, Long> amendedValuePairCounts) {
		this(useCaseId, useCaseLabel, inputFile, darwinCoreTermCount, singleRecordCount, darwinCoreTermCount,
				singleRecordCount, Map.of(), filledInValueCounts, amendedValuePairCounts);
	}

	/**
	 * Canonical constructor; copies maps defensively and substitutes empty defaults.
	 */
	public ExecutionSummaryMetadata {
		Map<String, List<String>> copiedFilters = new LinkedHashMap<>();
		(recordFilters == null ? Map.<String, List<String>>of() : recordFilters)
				.forEach((field, values) -> copiedFilters.put(field, List.copyOf(values == null ? List.of() : values)));
		recordFilters = Map.copyOf(copiedFilters);
		filledInValueCounts = Map.copyOf(filledInValueCounts == null ? Map.of() : filledInValueCounts);
		amendedValuePairCounts = Map.copyOf(amendedValuePairCounts == null ? Map.of() : amendedValuePairCounts);
	}

    /**
     * Returns an empty metadata instance, used as the default when no metadata is available.
     *
     * @return metadata with blank identity fields, zero counts, and empty tally maps
     */
    public static ExecutionSummaryMetadata empty() {
        return new ExecutionSummaryMetadata("", "", "", 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
    }

	/**
	 * @return the number of records excluded by active record filters
	 */
	public int excludedSingleRecordCount() {
		return Math.max(0, inputSingleRecordCount - filteredSingleRecordCount);
	}

	/**
	 * @return {@code true} if record filters were active for this run
	 */
	public boolean hasRecordFilters() {
		return !recordFilters.isEmpty();
	}
}
