/** RecordIdentificationCollector.java
 *
 * Gathers the terms that identify each execution record from its grain row and related rows.
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
package org.filteredpush.bdq_workbench.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.RecordIdentification;

/**
 * Gathers {@link RecordIdentification} from relational graphs: for each grain record, each of
 * {@link RecordIdentification#IDENTIFYING_TERMS} is taken from the grain row when populated
 * there, else from the first of the record's directly related tables whose rows carry it (the
 * distinct values across those rows), so that reports can name a record by terms that live on another table, such as
 * the material rows of a Darwin Core Data Package occurrence.
 */
public final class RecordIdentificationCollector {

	private RecordIdentificationCollector() {
	}

	/**
	 * Gathers the identifying terms of every grain record.
	 *
	 * @param relational the relational ingest result, before any view is applied
	 * @return the identification, empty when no record carries an identifying term
	 */
	public static RecordIdentification collect(RelationalIngestResult relational) {
		Map<String, Map<String, String>> termsByRecordId = new LinkedHashMap<>();
		Map<String, Set<String>> termsByTable = new LinkedHashMap<>();
		for (RecordGraph graph : relational.graphs()) {
			Map<String, String> terms = new LinkedHashMap<>();
			for (String term : RecordIdentification.IDENTIFYING_TERMS) {
				String value = value(graph.core(), term);
				if (value.isEmpty()) {
					value = relatedValue(graph, term, termsByTable);
				}
				if (!value.isEmpty()) {
					terms.put(term, value);
				}
			}
			if (!terms.isEmpty()) {
				termsByRecordId.put(graph.core().id(), terms);
			}
		}
		Map<String, List<String>> tables = new LinkedHashMap<>();
		termsByTable.forEach((table, terms) -> tables.put(table, RecordIdentification.IDENTIFYING_TERMS.stream()
				.filter(terms::contains)
				.toList()));
		return new RecordIdentification(termsByRecordId, tables);
	}

	/**
	 * Reads one term from a record's related rows, noting which table supplied it.
	 *
	 * @param graph the record's graph
	 * @param term the term's local name
	 * @param termsByTable receives the term under each table that supplied a value
	 * @return the distinct values joined with {@link RecordIdentification#VALUE_SEPARATOR}, or
	 *     {@code ""} when no related row carries the term
	 */
	private static String relatedValue(RecordGraph graph, String term, Map<String, Set<String>> termsByTable) {
		Set<String> values = new LinkedHashSet<>();
		for (Map.Entry<String, List<CanonicalRecord>> relation : graph.relatedByRelation().entrySet()) {
			boolean supplied = false;
			for (CanonicalRecord row : relation.getValue()) {
				String value = value(row, term);
				if (!value.isEmpty()) {
					supplied |= values.add(value);
				}
			}
			if (supplied) {
				/* Relational ingest keys each relation by the related table's name. */
				termsByTable.computeIfAbsent(relation.getKey(), table -> new LinkedHashSet<>()).add(term);
			}
			if (!values.isEmpty()) {
				break;
			}
		}
		return String.join(RecordIdentification.VALUE_SEPARATOR, values);
	}

	/**
	 * Reads a term from a row by local name, case-insensitively.
	 *
	 * @param row the row
	 * @param localName the term's local name
	 * @return the trimmed value, or {@code ""} when absent or blank
	 */
	private static String value(CanonicalRecord row, String localName) {
		List<String> matches = new ArrayList<>();
		row.terms().forEach((key, value) -> {
			if (value != null && !value.isBlank() && DarwinCoreTermResolver.localName(key).equalsIgnoreCase(localName)) {
				matches.add(value.trim());
			}
		});
		return matches.isEmpty() ? "" : matches.get(0);
	}
}
