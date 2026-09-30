/** RecordIdentification.java
 *
 * Terms that identify each execution record to a human reader, gathered from its related input rows.
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
 * The terms that let a person find each execution record in the original data, gathered at ingest
 * from the record's grain row and its directly related rows.
 *
 * <p>In a relational dataset the terms that name a record are often not on the grain table: a
 * Darwin Core Data Package keeps an occurrence's institution code, collection code and catalog
 * number on its material rows. No test reads those terms, so a view need not map them, yet the
 * reports need them to name the occurrence. They are gathered here from the raw input rows,
 * independently of the view, so reports can name records whatever the view mapped.
 *
 * @param termsByRecordId identifying term values by record ID and term local name; a term with
 *     several distinct values across a record's related rows holds them joined with
 *     {@link #VALUE_SEPARATOR}
 * @param termsByTable for each related (non-grain) table that supplied a value, the terms it
 *     supplied, in {@link #IDENTIFYING_TERMS} order
 */
public record RecordIdentification(
		Map<String, Map<String, String>> termsByRecordId,
		Map<String, List<String>> termsByTable) {

	/** The terms, by local name, that reports use to name a record. */
	public static final List<String> IDENTIFYING_TERMS = List.of(
			"institutionCode", "collectionCode", "catalogNumber", "datasetName", "datasetID");

	/** Separates distinct values of one term found on several related rows of a record. */
	public static final String VALUE_SEPARATOR = " | ";

	private static final RecordIdentification NONE = new RecordIdentification(Map.of(), Map.of());

	/**
	 * Canonical constructor; copies the maps defensively, preserving their order.
	 */
	public RecordIdentification {
		Map<String, Map<String, String>> records = new LinkedHashMap<>();
		if (termsByRecordId != null) {
			termsByRecordId.forEach((id, terms) -> records.put(id, Map.copyOf(terms)));
		}
		termsByRecordId = java.util.Collections.unmodifiableMap(records);
		Map<String, List<String>> tables = new LinkedHashMap<>();
		if (termsByTable != null) {
			termsByTable.forEach((table, terms) -> tables.put(table, List.copyOf(terms)));
		}
		termsByTable = java.util.Collections.unmodifiableMap(tables);
	}

	/**
	 * @return the identification used when ingest gathered none
	 */
	public static RecordIdentification none() {
		return NONE;
	}

	/**
	 * Returns one identifying term of a record.
	 *
	 * @param recordId the record ID
	 * @param localName the term's local name, one of {@link #IDENTIFYING_TERMS}
	 * @return the value, or {@code ""} when none was gathered
	 */
	public String term(String recordId, String localName) {
		return termsByRecordId.getOrDefault(recordId, Map.of()).getOrDefault(localName, "");
	}

	/**
	 * Reports whether a related table supplied any identifying term.
	 *
	 * @param table the table name
	 * @return {@code true} when it did
	 */
	public boolean identifiesFrom(String table) {
		return termsByTable.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(table));
	}

	/**
	 * Returns the identifying terms a related table supplied.
	 *
	 * @param table the table name
	 * @return the terms, empty when it supplied none
	 */
	public List<String> termsFrom(String table) {
		return termsByTable.entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(table))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(List.of());
	}
}
