/** RecordFilterSummary.java
 *
 * Summary of applying a record filter specification to an ingested dataset.
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of applying record filters to an ingested dataset.
 *
 * @param filteredDataset the subset of records selected for execution
 * @param originalRecordCount the number of records before filtering
 * @param filteredRecordCount the number of records selected after filtering
 * @param excludedRecordCount the number of records removed by filtering
 * @param originalDarwinCoreTermCount the number of distinct terms in the original dataset
 * @param filteredDarwinCoreTermCount the number of distinct terms remaining in the filtered subset
 * @param resolvedCriteria the active criteria keyed by resolved dataset field name
 * @param diagnostics human-readable filter diagnostics for preflight display
 */
public record RecordFilterSummary(
		RecordDataset filteredDataset,
		int originalRecordCount,
		int filteredRecordCount,
		int excludedRecordCount,
		int originalDarwinCoreTermCount,
		int filteredDarwinCoreTermCount,
		Map<String, List<String>> resolvedCriteria,
		List<String> diagnostics) {

	/**
	 * Canonical constructor; copies collections defensively and supplies empty defaults.
	 */
	public RecordFilterSummary {
		filteredDataset = filteredDataset == null ? new RecordDataset(List.of()) : filteredDataset;
		Map<String, List<String>> copiedCriteria = new LinkedHashMap<>();
		(resolvedCriteria == null ? Map.<String, List<String>>of() : resolvedCriteria)
				.forEach((field, values) -> copiedCriteria.put(field, List.copyOf(values == null ? List.of() : values)));
		resolvedCriteria = Map.copyOf(copiedCriteria);
		diagnostics = List.copyOf(diagnostics == null ? List.of() : new ArrayList<>(diagnostics));
	}

	/**
	 * Creates a no-filter summary over {@code dataset}.
	 *
	 * @param dataset the dataset to describe
	 * @return a summary representing an unfiltered execution set
	 */
	public static RecordFilterSummary unfiltered(RecordDataset dataset) {
		RecordDataset safeDataset = dataset == null ? new RecordDataset(List.of()) : dataset;
		int recordCount = safeDataset.records().size();
		int termCount = countDistinctTerms(safeDataset);
		return new RecordFilterSummary(
				safeDataset,
				recordCount,
				recordCount,
				0,
				termCount,
				termCount,
				Map.of(),
				List.of("No record filters configured"));
	}

	/**
	 * @return {@code true} if any active filter criteria were applied
	 */
	public boolean hasActiveFilters() {
		return !resolvedCriteria.isEmpty();
	}

	/**
	 * Counts distinct Darwin Core term names across a dataset.
	 *
	 * @param dataset the dataset to inspect
	 * @return the number of distinct terms present
	 */
	private static int countDistinctTerms(RecordDataset dataset) {
		java.util.Set<String> terms = new java.util.LinkedHashSet<>();
		dataset.records().forEach(record -> terms.addAll(record.terms().keySet()));
		return terms.size();
	}
}
