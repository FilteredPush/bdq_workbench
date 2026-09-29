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
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;
import org.filteredpush.bdq_workbench.model.SourceCell;

/**
 * Applies a dataset view to relational graphs and emits one canonical record per grain row.
 *
 * <p>Terms mapped from the grain table and from joins with a flattening
 * {@link DatasetViewCardinalityPolicy} land directly on that record. Joins with
 * {@link DatasetViewCardinalityPolicy#EXPAND} are not flattened: their rows are kept, projected
 * onto the terms the view maps from that table (with source-row provenance), as related rows of a
 * {@link RecordGraph} whose core is the flat record itself. The resulting dataset then carries
 * graphs, which is what lets execution run a test once per expanded row.
 */
public class ViewFlattener {

	/**
	 * Flattens relational graphs according to a reusable dataset view.
	 *
	 * @param relational relational ingest result
	 * @param view view definition to apply
	 * @return the view's dataset (with record graphs when any join uses
	 *     {@link DatasetViewCardinalityPolicy#EXPAND}) and non-fatal diagnostics
	 */
	public ViewFlattenResult flatten(RelationalIngestResult relational, DatasetView view) {
		if (!relational.coreTable().isBlank() && !relational.coreTable().equalsIgnoreCase(view.grainTable())) {
			throw new AppException("Dataset view grain table " + view.grainTable() + " does not match the table the "
					+ "relational graphs were built around (" + relational.coreTable() + "); ingest with the view's "
					+ "grain table so each record is one " + view.grainTable() + " row");
		}
		List<String> diagnostics = new ArrayList<>();
		warnAboutDuplicateFlattenedTerms(view, diagnostics);
		List<DatasetViewJoin> expandedJoins = view.joins().stream()
				.filter(join -> join.cardinalityPolicy() == DatasetViewCardinalityPolicy.EXPAND)
				.toList();
		List<CanonicalRecord> flattened = new ArrayList<>();
		List<RecordGraph> graphs = new ArrayList<>();
		for (RecordGraph graph : relational.graphs()) {
			CanonicalRecord record = flattenGraph(graph, view, expandedJoins, diagnostics);
			flattened.add(record);
			if (!expandedJoins.isEmpty()) {
				graphs.add(new RecordGraph(record, expandedRows(graph, view, expandedJoins)));
			}
		}
		return new ViewFlattenResult(new RecordDataset(flattened, graphs), diagnostics);
	}

	/**
	 * Builds the flat record for one grain row from every mapping not sourced by an expanded join.
	 *
	 * @param graph the grain row's relational graph
	 * @param view the view being applied
	 * @param expandedJoins the view's {@link DatasetViewCardinalityPolicy#EXPAND} joins
	 * @param diagnostics receives non-fatal diagnostics
	 * @return the flat record
	 */
	private CanonicalRecord flattenGraph(
			RecordGraph graph,
			DatasetView view,
			List<DatasetViewJoin> expandedJoins,
			List<String> diagnostics) {
		Map<String, String> terms = new LinkedHashMap<>();
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>();
		for (DatasetViewMapping mapping : view.mappings()) {
			if (isExpandedSource(mapping.sourceTable(), expandedJoins) || terms.containsKey(mapping.term())) {
				continue;
			}
			ValueSelection selected = selectValue(graph, mapping, view.grainTable(), view.joins(), diagnostics);
			terms.put(mapping.term(), selected.value());
			if (!selected.cells().isEmpty()) {
				provenance.put(mapping.term(), selected.cells());
			}
		}
		return new CanonicalRecord(graph.core().id(), terms, provenance);
	}

	/**
	 * Projects each expanded join's related rows onto the terms the view maps from that table.
	 *
	 * @param graph the grain row's relational graph
	 * @param view the view being applied
	 * @param expandedJoins the view's {@link DatasetViewCardinalityPolicy#EXPAND} joins
	 * @return projected related rows keyed by relation name
	 */
	private Map<String, List<CanonicalRecord>> expandedRows(
			RecordGraph graph,
			DatasetView view,
			List<DatasetViewJoin> expandedJoins) {
		Map<String, List<CanonicalRecord>> related = new LinkedHashMap<>();
		for (DatasetViewJoin join : expandedJoins) {
			List<DatasetViewMapping> mappings = view.mappings().stream()
					.filter(mapping -> mapping.sourceTable().equalsIgnoreCase(join.sourceTable()))
					.toList();
			List<CanonicalRecord> rows = graph.relatedByRelation().getOrDefault(join.relationName(), List.of()).stream()
					.map(row -> projectRow(row, join.sourceTable(), mappings))
					.toList();
			related.put(join.relationName(), rows);
		}
		return related;
	}

	/**
	 * Projects one related row onto view terms, keeping source-row provenance for each value.
	 *
	 * @param row the related row
	 * @param sourceTable the related row's table
	 * @param mappings the view mappings sourced from that table
	 * @return a record carrying only the mapped view terms
	 */
	private CanonicalRecord projectRow(CanonicalRecord row, String sourceTable, List<DatasetViewMapping> mappings) {
		Map<String, String> terms = new LinkedHashMap<>();
		Map<String, List<SourceCell>> provenance = new LinkedHashMap<>();
		for (DatasetViewMapping mapping : mappings) {
			terms.put(mapping.term(), row.terms().getOrDefault(mapping.sourceColumn(), ""));
			provenance.put(mapping.term(), sourceCells(row, sourceTable, mapping.sourceColumn(), mapping.term()));
		}
		return new CanonicalRecord(row.id(), terms, provenance);
	}

	/**
	 * Reports a term mapped from more than one flattened source; the first mapping wins. (A term may
	 * legitimately be mapped from the grain and from an expanded table, since expanded rows keep
	 * their own values.)
	 *
	 * @param view the view being applied
	 * @param diagnostics receives one diagnostic per duplicated term
	 */
	private static void warnAboutDuplicateFlattenedTerms(DatasetView view, List<String> diagnostics) {
		List<DatasetViewJoin> expandedJoins = view.joins().stream()
				.filter(join -> join.cardinalityPolicy() == DatasetViewCardinalityPolicy.EXPAND)
				.toList();
		Map<String, String> firstSource = new LinkedHashMap<>();
		for (DatasetViewMapping mapping : view.mappings()) {
			if (isExpandedSource(mapping.sourceTable(), expandedJoins)) {
				continue;
			}
			String previous = firstSource.putIfAbsent(mapping.term(), mapping.sourceTable());
			if (previous != null) {
				diagnostics.add("Term " + mapping.term() + " is mapped from both " + previous + " and "
						+ mapping.sourceTable() + "; using " + previous);
			}
		}
	}

	/**
	 * Reports whether a mapping's source table is read through an expanded join.
	 *
	 * @param sourceTable the mapping's source table
	 * @param expandedJoins the view's {@link DatasetViewCardinalityPolicy#EXPAND} joins
	 * @return {@code true} when the table's rows are expanded rather than flattened
	 */
	private static boolean isExpandedSource(String sourceTable, List<DatasetViewJoin> expandedJoins) {
		return expandedJoins.stream().anyMatch(join -> join.sourceTable().equalsIgnoreCase(sourceTable));
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
