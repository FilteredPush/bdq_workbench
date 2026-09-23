/** DefaultRecordFilterService.java
 *
 * Default exact-match record filtering implementation for canonical Darwin Core datasets.
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
package org.filteredpush.bdq_workbench.filtering;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordFilterSpec;
import org.filteredpush.bdq_workbench.model.RecordFilterSummary;
import org.filteredpush.bdq_workbench.model.RecordGraph;

/**
 * Applies pre-execution record filters to canonical datasets.
 *
 * <p>Field names are resolved case-insensitively by full name or local name, using the same alias
 * rules as binding. Values are matched exactly and case-sensitively against the canonical string
 * values already produced by ingestion.
 */
public class DefaultRecordFilterService implements RecordFilterService {

	/**
	 * Applies {@code filterSpec} to {@code dataset}.
	 *
	 * @param dataset the ingested dataset
	 * @param filterSpec the configured filter specification
	 * @return the filtered dataset summary
	 * @throws AppException if a requested field does not resolve uniquely against the dataset
	 */
	@Override
	public RecordFilterSummary apply(RecordDataset dataset, RecordFilterSpec filterSpec) {
		RecordDataset safeDataset = dataset == null ? new RecordDataset(List.of()) : dataset;
		RecordFilterSpec safeSpec = filterSpec == null ? RecordFilterSpec.empty() : filterSpec;
		if (safeSpec.isEmpty()) {
			return RecordFilterSummary.unfiltered(safeDataset);
		}

		Map<String, List<String>> aliasIndex = DarwinCoreTermResolver.indexAvailableTerms(collectAvailableTerms(safeDataset));
		Map<String, LinkedHashSet<String>> resolvedCriteria = new LinkedHashMap<>();
		List<String> diagnostics = new ArrayList<>();
		safeSpec.criteria().forEach((requestedField, values) -> {
			DarwinCoreTermResolver.Resolution resolution = DarwinCoreTermResolver.resolve(requestedField, aliasIndex);
			if (resolution.matches().isEmpty()) {
				throw new AppException("Unknown record filter field: " + requestedField);
			}
			if (resolution.isAmbiguous()) {
				throw new AppException("Ambiguous record filter field: " + requestedField
						+ " matched " + resolution.matches());
			}
			String resolvedField = resolution.preferredMatch();
			resolvedCriteria.computeIfAbsent(resolvedField, key -> new LinkedHashSet<>()).addAll(values);
			diagnostics.add(requestedField.equals(resolvedField)
					? "Record filter field " + requestedField + " matched input field " + resolvedField
					: "Record filter field " + requestedField + " resolved to input field " + resolvedField);
		});

		Map<String, List<String>> immutableCriteria = new LinkedHashMap<>();
		resolvedCriteria.forEach((field, values) -> immutableCriteria.put(field, List.copyOf(values)));

		List<CanonicalRecord> kept = safeDataset.records().stream()
				.filter(record -> matches(record, immutableCriteria))
				.toList();
		Set<String> keptIds = kept.stream().map(CanonicalRecord::id).collect(java.util.stream.Collectors.toSet());
		List<RecordGraph> keptGraphs = safeDataset.recordGraphs().stream()
				.filter(graph -> keptIds.contains(graph.core().id()))
				.toList();
		RecordDataset filteredDataset = keptGraphs.isEmpty()
				? new RecordDataset(kept)
				: new RecordDataset(kept, keptGraphs);
		if (kept.isEmpty()) {
			diagnostics.add("No records matched the configured record filters");
		}
		return new RecordFilterSummary(
				filteredDataset,
				safeDataset.records().size(),
				kept.size(),
				safeDataset.records().size() - kept.size(),
				collectAvailableTerms(safeDataset).size(),
				collectAvailableTerms(filteredDataset).size(),
				immutableCriteria,
				diagnostics);
	}

	/**
	 * Checks whether a record satisfies every configured field criterion.
	 *
	 * @param record the record to inspect
	 * @param criteria resolved field criteria keyed by actual dataset field name
	 * @return {@code true} if the record matches all configured fields
	 */
	private static boolean matches(CanonicalRecord record, Map<String, List<String>> criteria) {
		for (Map.Entry<String, List<String>> entry : criteria.entrySet()) {
			String value = record.terms().get(entry.getKey());
			if (value == null || value.isBlank() || !entry.getValue().contains(value)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Collects distinct dataset term names while preserving encounter order.
	 *
	 * @param dataset the dataset to inspect
	 * @return the distinct set of term names present in the dataset
	 */
	private static Set<String> collectAvailableTerms(RecordDataset dataset) {
		Set<String> terms = new java.util.LinkedHashSet<>();
		dataset.records().forEach(record -> terms.addAll(record.terms().keySet()));
		dataset.recordGraphs().forEach(graph -> graph.relatedByRelation().values().forEach(related ->
				related.forEach(record -> terms.addAll(record.terms().keySet()))));
		return terms;
	}
}
