/** DatasetInputDescriber.java
 *
 * Builds the DatasetInputDescription that records how an ingested dataset's records were constructed.
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.InputTable;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;
import org.filteredpush.bdq_workbench.model.DatasetViewJoin;
import org.filteredpush.bdq_workbench.model.DatasetViewMapping;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.model.TableSchema;

/**
 * Builds {@link DatasetInputDescription}s for each of {@link DefaultIngestService}'s ingest paths.
 *
 * <p>Per-relation multiplicity is measured from the relational graphs before any flattening
 * ({@link RelationalIngestResult#measureRelation}), since a flattened dataset no longer carries
 * the related rows it collapsed.
 */
final class DatasetInputDescriber {

	/** Record count used when a table's row count was not recorded. */
	private static final int UNKNOWN_RECORD_COUNT = -1;

	private DatasetInputDescriber() {
	}

	/**
	 * Describes a dataset read directly from one table by a flat ingestor.
	 *
	 * @param tableName the name to show for the table (the requested table, or the input file name)
	 * @param dataset the flat dataset that was read
	 * @return a {@link ViewMode#SINGLE_TABLE} description
	 */
	static DatasetInputDescription singleTable(String tableName, RecordDataset dataset) {
		Set<String> columns = new LinkedHashSet<>();
		dataset.records().forEach(record -> columns.addAll(record.terms().keySet()));
		InputTable table = new InputTable(tableName, "", dataset.records().size(), List.copyOf(columns));
		return new DatasetInputDescription(
				ViewMode.SINGLE_TABLE,
				"",
				tableName,
				dataset.records().size(),
				List.of(table),
				List.of(),
				List.of(),
				List.of());
	}

	/**
	 * Describes relational graphs used as-is, retaining related rows under each core record.
	 *
	 * @param relational the relational ingest result
	 * @param viewSource human-readable origin of the view
	 * @return a {@link ViewMode#STRUCTURED} description, or {@link ViewMode#SINGLE_TABLE} when no
	 *     related table was attached to any core record
	 */
	static DatasetInputDescription structured(RelationalIngestResult relational, String viewSource) {
		List<ViewRelation> relations = new ArrayList<>();
		for (String relationName : relationNames(relational)) {
			relations.add(relational.measureRelation(relationName, relationName, null, List.of()));
		}
		return new DatasetInputDescription(
				relations.isEmpty() ? ViewMode.SINGLE_TABLE : ViewMode.STRUCTURED,
				relations.isEmpty() ? "" : viewSource,
				relational.coreTable(),
				relational.graphs().size(),
				inputTables(relational),
				relational.schema().relationships(),
				relations,
				List.of());
	}

	/**
	 * Describes relational graphs passed through a dataset view.
	 *
	 * @param relational the relational ingest result the view was applied to
	 * @param view the applied dataset view
	 * @param viewSource human-readable origin of the view
	 * @param viewRecordCount the number of flattened records the view produced
	 * @return a {@link ViewMode#FLATTENED} description, or {@link ViewMode#STRUCTURED} when any
	 *     join uses {@link DatasetViewCardinalityPolicy#EXPAND} and so retains related rows
	 */
	static DatasetInputDescription flattened(
			RelationalIngestResult relational,
			DatasetView view,
			String viewSource,
			int viewRecordCount) {
		String grainTable = view.grainTable().isBlank() ? relational.coreTable() : view.grainTable();
		List<ViewRelation> relations = new ArrayList<>();
		for (DatasetViewJoin join : view.joins()) {
			relations.add(relational.measureRelation(
					join.relationName(),
					join.sourceTable(),
					join.cardinalityPolicy(),
					mappedTerms(view, join.sourceTable())));
		}
		boolean expands = view.joins().stream()
				.anyMatch(join -> join.cardinalityPolicy() == DatasetViewCardinalityPolicy.EXPAND);
		return new DatasetInputDescription(
				expands ? ViewMode.STRUCTURED : ViewMode.FLATTENED,
				viewSource,
				grainTable,
				viewRecordCount,
				inputTables(relational),
				relational.schema().relationships(),
				relations,
				mappedTerms(view, grainTable));
	}

	/**
	 * Summarizes every table in the relational schema with its recorded row count.
	 *
	 * @param relational the relational ingest result
	 * @return the input tables in schema declaration order
	 */
	private static List<InputTable> inputTables(RelationalIngestResult relational) {
		return relational.schema().tables().stream()
				.map(table -> new InputTable(
						table.name(),
						table.rowType(),
						relational.tableRecordCounts().getOrDefault(table.name(), UNKNOWN_RECORD_COUNT),
						table.columns()))
				.toList();
	}

	/**
	 * Lists the relation keys attached to any core record, in schema table order.
	 *
	 * @param relational the relational ingest result
	 * @return the relation names present on the graphs
	 */
	private static List<String> relationNames(RelationalIngestResult relational) {
		Set<String> present = new LinkedHashSet<>();
		relational.graphs().forEach(graph -> present.addAll(graph.relatedByRelation().keySet()));
		List<String> ordered = new ArrayList<>();
		relational.schema().tables().stream()
				.map(TableSchema::name)
				.filter(present::remove)
				.forEach(ordered::add);
		present.stream().sorted().forEach(ordered::add);
		return ordered;
	}

	/**
	 * Lists the terms a view maps from one source table.
	 *
	 * @param view the dataset view
	 * @param sourceTable the source table to match, case-insensitively
	 * @return the mapped terms, in mapping order
	 */
	private static List<String> mappedTerms(DatasetView view, String sourceTable) {
		return view.mappings().stream()
				.filter(mapping -> mapping.sourceTable().equalsIgnoreCase(sourceTable))
				.map(DatasetViewMapping::term)
				.toList();
	}
}
