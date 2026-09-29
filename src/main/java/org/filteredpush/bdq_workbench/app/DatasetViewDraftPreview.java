/** DatasetViewDraftPreview.java
 *
 * Builds the dataset-view builder's preview rows, observed multiplicity, and diagnostics for a draft view.
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
package org.filteredpush.bdq_workbench.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.filteredpush.bdq_workbench.ingest.RelationalIngestResult;
import org.filteredpush.bdq_workbench.ingest.ViewFlattenResult;
import org.filteredpush.bdq_workbench.ingest.ViewFlattener;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.RecordGraph;

/**
 * Previews what a draft {@link DatasetView} will feed to test execution, for the dataset-view
 * builder dialog in {@link BdqWorkbenchGui}.
 *
 * <p>The preview shows the first few grain records as flat rows and, beneath each, the related
 * rows kept by any {@link DatasetViewCardinalityPolicy#EXPAND} join, so the user can see which
 * values each per-row test evaluation will receive. The summary explains, per join, the
 * multiplicity observed in the data and what the chosen policy does with it, and warns where the
 * policy will discard rows, produce values that fail tests, or make tests unrunnable. Kept free of
 * Swing so it can be unit tested.
 *
 * @param columns the preview table's column names
 * @param rows the preview table's rows, aligned to {@code columns}
 * @param summary informational lines describing the view
 * @param warnings lines describing likely problems with the draft
 */
record DatasetViewDraftPreview(
		List<String> columns,
		List<List<String>> rows,
		List<String> summary,
		List<String> warnings) {

	/** Grain records shown in the preview table. */
	static final int PREVIEW_GRAIN_RECORDS = 5;
	/** Expanded related rows shown per relation under each previewed grain record. */
	static final int PREVIEW_EXPANDED_ROWS = 3;
	/** Flattener diagnostics listed before the remainder is summarized as a count. */
	static final int MAX_LISTED_DIAGNOSTICS = 20;
	/** Preview column naming each row's place in the view. */
	static final String ROW_COLUMN = "View row";
	/** Preview column holding each row's grain record identifier. */
	static final String RECORD_ID_COLUMN = "recordId";

	/**
	 * Canonical constructor; copies list components defensively.
	 */
	DatasetViewDraftPreview {
		columns = List.copyOf(columns);
		rows = rows.stream().map(List::copyOf).toList();
		summary = List.copyOf(summary);
		warnings = List.copyOf(warnings);
	}

	/**
	 * Builds the preview for one draft view over relational graphs built for its grain table.
	 *
	 * @param relational relational ingest result whose core table is the view's grain table
	 * @param view the draft view
	 * @return the preview
	 */
	static DatasetViewDraftPreview build(RelationalIngestResult relational, DatasetView view) {
		ViewFlattenResult flattened = new ViewFlattener().flatten(relational, view);
		List<String> columns = new ArrayList<>();
		columns.add(RECORD_ID_COLUMN);
		columns.add(ROW_COLUMN);
		Set<String> terms = new LinkedHashSet<>();
		view.mappings().forEach(mapping -> terms.add(mapping.term()));
		columns.addAll(terms);
		List<String> summary = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		summary.add(flattened.dataset().records().size() + " execution record(s), one per "
				+ view.grainTable() + " row.");
		for (DatasetViewJoin join : view.joins()) {
			describeJoin(relational, view, join, summary, warnings);
		}
		warnAboutMultipleExpandedJoins(view, warnings);
		addFlattenerDiagnostics(flattened.diagnostics(), warnings);
		return new DatasetViewDraftPreview(columns, previewRows(flattened.dataset(), view, columns), summary, warnings);
	}

	/**
	 * Describes the related-row multiplicity observed for one relation, for the join grid.
	 *
	 * @param relational relational ingest result whose core table is the view's grain table
	 * @param relationName the relation to measure
	 * @return a short multiplicity description
	 */
	static String observedMultiplicity(RelationalIngestResult relational, String relationName) {
		ViewRelation relation = relational.measureRelation(relationName, relationName, null, List.of());
		if (relation.relatedRowCount() == 0) {
			return "no related rows";
		}
		if (relation.maxRowsPerCoreRecord() <= 1) {
			return "0..1 per record";
		}
		return "up to " + relation.maxRowsPerCoreRecord() + " per record ("
				+ relation.coreRecordsWithMultipleRows() + " with >1)";
	}

	/**
	 * Renders the summary and warnings as text for the builder's diagnostics pane.
	 *
	 * @return the rendered diagnostics
	 */
	String renderDiagnostics() {
		StringBuilder builder = new StringBuilder();
		summary.forEach(line -> builder.append(line).append('\n'));
		if (warnings.isEmpty()) {
			builder.append("No warnings.");
		} else {
			builder.append("\nWarnings:\n");
			warnings.forEach(line -> builder.append(" - ").append(line).append('\n'));
		}
		return builder.toString();
	}

	/**
	 * Adds the summary line, and any warning, for one included join.
	 *
	 * @param relational relational ingest result
	 * @param view the draft view
	 * @param join the join to describe
	 * @param summary receives informational lines
	 * @param warnings receives warnings
	 */
	private static void describeJoin(
			RelationalIngestResult relational,
			DatasetView view,
			DatasetViewJoin join,
			List<String> summary,
			List<String> warnings) {
		List<String> mappedTerms = view.mappings().stream()
				.filter(mapping -> mapping.sourceTable().equalsIgnoreCase(join.sourceTable()))
				.map(DatasetViewMapping::term)
				.toList();
		ViewRelation relation = relational.measureRelation(
				join.relationName(), join.sourceTable(), join.cardinalityPolicy(), mappedTerms);
		DatasetViewCardinalityPolicy policy = join.cardinalityPolicy();
		String prefix = join.sourceTable() + " [" + policy.name() + "]: ";
		if (mappedTerms.isEmpty()) {
			warnings.add(prefix + "no terms are mapped from this table, so joining it has no effect.");
			return;
		}
		boolean multiple = relation.coreRecordsWithMultipleRows() > 0;
		String counts = relation.relatedRowCount() + " related row(s), up to " + relation.maxRowsPerCoreRecord()
				+ " per grain record";
		if (policy == DatasetViewCardinalityPolicy.EXPAND) {
			summary.add(prefix + counts + ". Tests reading " + String.join(", ", mappedTerms)
					+ " run once per related row (about " + expandedSubjectCount(relational, join)
					+ " evaluation(s) per such test).");
			return;
		}
		if (!multiple) {
			summary.add(prefix + counts + "; at most one row per grain record, so no rows are lost.");
			return;
		}
		String affected = relation.coreRecordsWithMultipleRows() + " grain record(s) have more than one row (up to "
				+ relation.maxRowsPerCoreRecord() + ")";
		summary.add(prefix + counts + ".");
		switch (policy) {
			case FIRST_ROW -> warnings.add(prefix + affected + "; only the first row is used. Choose EXPAND to test "
					+ "every row.");
			case AGGREGATE -> warnings.add(prefix + affected + "; their values are joined with \" | \", which will "
					+ "usually fail tests on " + String.join(", ", mappedTerms) + ". Choose EXPAND to test every row.");
			case REJECT -> warnings.add(prefix + affected + "; " + String.join(", ", mappedTerms)
					+ " will be empty for those records. Choose EXPAND to test every row.");
			default -> {
			}
		}
	}

	/**
	 * Counts the evaluation subjects a test reading an expanded join's terms would run over.
	 *
	 * @param relational relational ingest result
	 * @param join the expanded join
	 * @return one per related row, plus one for each grain record with no related rows
	 */
	private static int expandedSubjectCount(RelationalIngestResult relational, DatasetViewJoin join) {
		int count = 0;
		for (RecordGraph graph : relational.graphs()) {
			count += Math.max(1, graph.relatedByRelation().getOrDefault(join.relationName(), List.of()).size());
		}
		return count;
	}

	/**
	 * Warns when more than one join expands, since a test reading both cannot run.
	 *
	 * @param view the draft view
	 * @param warnings receives the warning
	 */
	private static void warnAboutMultipleExpandedJoins(DatasetView view, List<String> warnings) {
		List<String> expanded = view.joins().stream()
				.filter(join -> join.cardinalityPolicy() == DatasetViewCardinalityPolicy.EXPAND)
				.map(DatasetViewJoin::sourceTable)
				.toList();
		if (expanded.size() > 1) {
			warnings.add("More than one table is expanded (" + String.join(", ", expanded) + "). A test whose inputs "
					+ "come from two expanded tables cannot run and will report an error; expand only the tables whose "
					+ "rows each need testing.");
		}
	}

	/**
	 * Adds the flattener's own diagnostics, listing at most {@link #MAX_LISTED_DIAGNOSTICS}.
	 *
	 * @param diagnostics the flattener diagnostics
	 * @param warnings receives the diagnostics
	 */
	private static void addFlattenerDiagnostics(List<String> diagnostics, List<String> warnings) {
		diagnostics.stream().limit(MAX_LISTED_DIAGNOSTICS).forEach(warnings::add);
		if (diagnostics.size() > MAX_LISTED_DIAGNOSTICS) {
			warnings.add("... and " + (diagnostics.size() - MAX_LISTED_DIAGNOSTICS) + " more flattening diagnostic(s)");
		}
	}

	/**
	 * Builds the preview table rows: each previewed grain record, then its expanded related rows.
	 *
	 * @param dataset the view's dataset
	 * @param view the draft view
	 * @param columns the preview columns
	 * @return the preview rows
	 */
	private static List<List<String>> previewRows(RecordDataset dataset, DatasetView view, List<String> columns) {
		List<List<String>> rows = new ArrayList<>();
		List<CanonicalRecord> records = dataset.records();
		for (int index = 0; index < Math.min(PREVIEW_GRAIN_RECORDS, records.size()); index++) {
			CanonicalRecord record = records.get(index);
			rows.add(row(columns, record.id(), view.grainTable(), record));
			if (dataset.hasStructuredGraphs()) {
				addExpandedRows(rows, columns, dataset.recordGraphs().get(index), view);
			}
		}
		return rows;
	}

	/**
	 * Adds the previewed expanded rows beneath one grain record.
	 *
	 * @param rows receives the preview rows
	 * @param columns the preview columns
	 * @param graph the grain record's graph of expanded rows
	 * @param view the draft view
	 */
	private static void addExpandedRows(List<List<String>> rows, List<String> columns, RecordGraph graph,
			DatasetView view) {
		for (DatasetViewJoin join : view.joins()) {
			if (join.cardinalityPolicy() != DatasetViewCardinalityPolicy.EXPAND) {
				continue;
			}
			List<CanonicalRecord> related = graph.relatedByRelation().getOrDefault(join.relationName(), List.of());
			for (int index = 0; index < Math.min(PREVIEW_EXPANDED_ROWS, related.size()); index++) {
				rows.add(row(columns, graph.core().id(),
						"  ↳ " + join.sourceTable() + " row " + (index + 1) + " of " + related.size(),
						related.get(index)));
			}
			if (related.size() > PREVIEW_EXPANDED_ROWS) {
				rows.add(row(columns, graph.core().id(),
						"  ↳ " + join.sourceTable() + ": " + (related.size() - PREVIEW_EXPANDED_ROWS) + " more row(s)",
						null));
			}
			if (related.isEmpty()) {
				rows.add(row(columns, graph.core().id(), "  ↳ no " + join.sourceTable() + " rows (tested once, blank)",
						null));
			}
		}
	}

	/**
	 * Builds one preview row.
	 *
	 * @param columns the preview columns
	 * @param recordId the grain record identifier
	 * @param rowLabel the row's place in the view
	 * @param record the record supplying term values, or {@code null} for a label-only row
	 * @return the row's cell values
	 */
	private static List<String> row(List<String> columns, String recordId, String rowLabel, CanonicalRecord record) {
		List<String> values = new ArrayList<>();
		values.add(recordId);
		values.add(rowLabel);
		for (String column : columns.subList(2, columns.size())) {
			values.add(record == null ? "" : record.terms().getOrDefault(column, ""));
		}
		return values;
	}
}
