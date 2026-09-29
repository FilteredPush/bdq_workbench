/** RecordDataset.java
 *
 * Collection of canonical records produced by ingestion and passed through execution.
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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection of canonical records.
 *
 * @param records the canonical records in this dataset
 * @param recordGraphs optional relational graphs aligned to the dataset's core records; empty for
 *     flat-only datasets
 * @param inputDescription how these records were built from the input tables (grain table,
 *     view mode, per-table record counts); {@link DatasetInputDescription#none()} when ingest did
 *     not record it
 */
public record RecordDataset(
		List<CanonicalRecord> records,
		List<RecordGraph> recordGraphs,
		DatasetInputDescription inputDescription) {

	/**
	 * Creates a flat-only dataset.
	 *
	 * @param records the canonical records in this dataset
	 */
	public RecordDataset(List<CanonicalRecord> records) {
		this(records, List.of());
	}

	/**
	 * Creates a dataset with no recorded input description.
	 *
	 * @param records the canonical records in this dataset
	 * @param recordGraphs optional relational graphs aligned to the dataset's core records
	 */
	public RecordDataset(List<CanonicalRecord> records, List<RecordGraph> recordGraphs) {
		this(records, recordGraphs, DatasetInputDescription.none());
	}

	/**
	 * Canonical constructor; copies list components defensively and substitutes
	 * {@link DatasetInputDescription#none()} for a null input description.
	 */
	public RecordDataset {
		records = List.copyOf(records == null ? List.of() : records);
		recordGraphs = List.copyOf(recordGraphs == null ? List.of() : recordGraphs);
		inputDescription = inputDescription == null ? DatasetInputDescription.none() : inputDescription;
	}

	/**
	 * Returns a copy of this dataset carrying the given input description.
	 *
	 * @param description how the records were built from the input tables
	 * @return a dataset with the same records and graphs and the supplied description
	 */
	public RecordDataset withInputDescription(DatasetInputDescription description) {
		return new RecordDataset(records, recordGraphs, description);
	}

	/**
	 * Reports whether this dataset carries relational graphs for structured execution.
	 *
	 * @return {@code true} when relational graphs are available
	 */
	public boolean hasStructuredGraphs() {
		return !recordGraphs.isEmpty();
	}

	/**
	 * Creates an independent copy of this dataset, preserving any shared record objects between the
	 * flat record list and relational graphs so amendment writes remain visible across both views.
	 *
	 * @return a deep copy of this dataset
	 */
	public RecordDataset copy() {
		Map<CanonicalRecord, CanonicalRecord> copies = new IdentityHashMap<>();
		List<RecordGraph> copiedGraphs = recordGraphs.stream()
				.map(graph -> new RecordGraph(
						copyRecord(graph.core(), copies),
						graph.relatedByRelation().entrySet().stream().collect(
								java.util.stream.Collectors.toMap(
										Map.Entry::getKey,
										entry -> entry.getValue().stream()
												.map(record -> copyRecord(record, copies))
												.toList(),
										(left, right) -> left,
										java.util.LinkedHashMap::new))))
				.toList();
		List<CanonicalRecord> copiedRecords = records.stream()
				.map(record -> copyRecord(record, copies))
				.toList();
		return new RecordDataset(copiedRecords, copiedGraphs, inputDescription);
	}

	/**
	 * Copies one record once for the lifetime of a dataset copy operation.
	 *
	 * @param record the record to copy
	 * @param copies memoized copies keyed by original object identity
	 * @return the copied record
	 */
	private static CanonicalRecord copyRecord(
			CanonicalRecord record,
			Map<CanonicalRecord, CanonicalRecord> copies) {
		return copies.computeIfAbsent(record, CanonicalRecord::copy);
	}
}
