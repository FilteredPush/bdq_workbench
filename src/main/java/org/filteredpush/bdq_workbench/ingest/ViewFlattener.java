/** ViewFlattener.java
 *
 * Flattens relational record graphs with a reusable DatasetView.
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SourceCell;

/**
 * Applies a dataset view to relational graphs and emits flat canonical records.
 */
public class ViewFlattener {

	/**
	 * Flattens relational graphs according to a reusable dataset view.
	 *
	 * @param relational relational ingest result
	 * @param view view definition to apply
	 * @return flattened dataset and non-fatal diagnostics
	 */
	public ViewFlattenResult flatten(RelationalIngestResult relational, DatasetView view) {
		List<String> diagnostics = new ArrayList<>();
		List<CanonicalRecord> flattened = new ArrayList<>();
		for (RecordGraph graph : relational.graphs()) {
			Map<String, String> terms = new LinkedHashMap<>();
			Map<String, List<SourceCell>> provenance = new LinkedHashMap<>();
			for (DatasetViewMapping mapping : view.mappings()) {
				ValueSelection selected = selectValue(graph, mapping, view.grainTable(), view.joins(), diagnostics);
				terms.put(mapping.term(), selected.value());
				if (!selected.cells().isEmpty()) {
					provenance.put(mapping.term(), selected.cells());
				}
			}
			flattened.add(new CanonicalRecord(graph.core().id(), terms, provenance));
		}
		return new ViewFlattenResult(new RecordDataset(flattened), diagnostics);
	}

	/**
	 * Resolves one mapping from either the core row or one related relation.
	 */
	private ValueSelection selectValue(RecordGraph graph, DatasetViewMapping mapping, String grainTable,
			List<DatasetViewJoin> joins,
			List<String> diagnostics) {
		if (mapping.sourceTable().equalsIgnoreCase(grainTable)) {
			String value = graph.core().terms().getOrDefault(mapping.sourceColumn(), "");
			return new ValueSelection(value, sourceCells(graph.core(), grainTable, mapping.sourceColumn(), mapping.term()));
		}
		DatasetViewJoin join = joins.stream()
				.filter(candidate -> candidate.sourceTable().equalsIgnoreCase(mapping.sourceTable()))
				.findFirst()
				.orElse(null);
		if (join == null) {
			diagnostics.add("View mapping for term " + mapping.term() + " references source table "
					+ mapping.sourceTable() + " but the view has no join for it");
			return ValueSelection.empty();
		}
		List<CanonicalRecord> related = graph.relatedByRelation().getOrDefault(join.relationName(), List.of());
		if (related.isEmpty()) {
			return ValueSelection.empty();
		}
		List<ValueSelection> selections = related.stream()
				.map(row -> new ValueSelection(
						row.terms().getOrDefault(mapping.sourceColumn(), ""),
						sourceCells(row, join.sourceTable(), mapping.sourceColumn(), mapping.term())))
				.toList();
		if (related.size() > 1 && join.cardinalityPolicy() == DatasetViewCardinalityPolicy.REJECT) {
			diagnostics.add("Cardinality conflict for relation " + join.relationName() + " on record "
					+ graph.core().id() + " while mapping term " + mapping.term());
			return ValueSelection.empty();
		}
		if (join.cardinalityPolicy() == DatasetViewCardinalityPolicy.FIRST_ROW) {
			return selections.get(0);
		}
		if (join.cardinalityPolicy() == DatasetViewCardinalityPolicy.AGGREGATE) {
			List<ValueSelection> included = selections.stream()
					.filter(selection -> !selection.value().isBlank())
					.toList();
			String aggregated = included.stream()
					.map(ValueSelection::value)
					.collect(Collectors.joining(" | "));
			List<SourceCell> cells = included.stream().flatMap(selection -> selection.cells().stream()).toList();
			return new ValueSelection(aggregated, cells);
		}
		return selections.get(0);
	}

	/**
	 * Builds source-cell provenance for one value read from one row.
	 */
	private List<SourceCell> sourceCells(CanonicalRecord row, String sourceTable, String sourceColumn, String term) {
		List<SourceCell> existing = row.provenanceByTerm().get(sourceColumn);
		if (existing != null && !existing.isEmpty()) {
			return existing.stream()
					.map(cell -> new SourceCell(cell.table(), cell.sourceLocation(), cell.rowRef(), sourceColumn, term))
					.toList();
		}
		return List.of(new SourceCell(sourceTable, sourceTable, row.id(), sourceColumn, term));
	}

	private record ValueSelection(String value, List<SourceCell> cells) {

		private static ValueSelection empty() {
			return new ValueSelection("", List.of());
		}
	}
}
