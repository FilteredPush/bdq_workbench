/** BuiltInDatasetViews.java
 *
 * Built-in reusable dataset view definitions.
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
import java.util.List;
import java.util.Optional;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;
import org.filteredpush.bdq_workbench.model.TableSchema;

/**
 * Selector for built-in default dataset views.
 */
final class BuiltInDatasetViews {

	private BuiltInDatasetViews() {
	}

	/**
	 * Picks an applicable built-in view for the given schema, if any.
	 *
	 * @param schema discovered schema
	 * @param diagnostics receives applicability diagnostics
	 * @return built-in view when applicable
	 */
	static Optional<DatasetView> select(DatasetSchema schema, List<String> diagnostics) {
		Optional<DatasetView> eventOccurrence = eventCoreOccurrenceExtension(schema);
		if (eventOccurrence.isPresent()) {
			return eventOccurrence;
		}
		Optional<DatasetView> dataPackage = dwcDataPackageOccurrenceView(schema);
		if (dataPackage.isPresent()) {
			return dataPackage;
		}
		diagnostics.add("No built-in dataset view matched the dataset schema fingerprint "
				+ schema.schemaFingerprint() + "; falling back to flat ingest");
		return Optional.empty();
	}

	private static Optional<DatasetView> dwcDataPackageOccurrenceView(DatasetSchema schema) {
		TableSchema occurrence = findByRowType(schema, "OCCURRENCE");
		if (occurrence == null) {
			return Optional.empty();
		}
		if (schema.relationships().isEmpty()) {
			return Optional.empty();
		}
		List<DatasetViewJoin> joins = new ArrayList<>();
		for (RelationshipSchema relation : schema.relationships()) {
			if (relation.toTable().equals(occurrence.name())) {
				joins.add(new DatasetViewJoin(relation.relationName(), relation.fromTable(),
						DatasetViewCardinalityPolicy.FIRST_ROW));
			}
		}
		if (joins.isEmpty()) {
			return Optional.empty();
		}
		List<DatasetViewMapping> mappings = defaultOccurrenceMappings(occurrence.name(), joins);
		return Optional.of(new DatasetView(occurrence.name(), schema.schemaFingerprint(), joins, mappings));
	}

	private static Optional<DatasetView> eventCoreOccurrenceExtension(DatasetSchema schema) {
		TableSchema event = findByRowType(schema, "EVENT");
		TableSchema occurrence = findByRowType(schema, "OCCURRENCE");
		if (event == null || occurrence == null) {
			return Optional.empty();
		}
		boolean hasEventToOccurrence = schema.relationships().stream()
				.anyMatch(relation -> relation.toTable().equals(event.name())
						&& relation.fromTable().equals(occurrence.name()));
		if (!hasEventToOccurrence) {
			return Optional.empty();
		}
		List<DatasetViewJoin> joins = List.of(new DatasetViewJoin(
				occurrence.name(),
				occurrence.name(),
				DatasetViewCardinalityPolicy.FIRST_ROW));
		List<DatasetViewMapping> mappings = defaultOccurrenceMappings(occurrence.name(), joins);
		return Optional.of(new DatasetView(event.name(), schema.schemaFingerprint(), joins, mappings));
	}

	private static List<DatasetViewMapping> defaultOccurrenceMappings(String occurrenceTable, List<DatasetViewJoin> joins) {
		String sourceTable = joins.isEmpty() ? "core" : occurrenceTable;
		return List.of(
				new DatasetViewMapping("occurrenceID", sourceTable, "occurrenceID"),
				new DatasetViewMapping("scientificName", sourceTable, "scientificName"),
				new DatasetViewMapping("eventDate", sourceTable, "eventDate"),
				new DatasetViewMapping("decimalLatitude", sourceTable, "decimalLatitude"),
				new DatasetViewMapping("decimalLongitude", sourceTable, "decimalLongitude"));
	}

	private static TableSchema findByRowType(DatasetSchema schema, String rowType) {
		return schema.tables().stream()
				.filter(table -> table.rowType().equalsIgnoreCase(rowType))
				.findFirst()
				.orElse(null);
	}
}
