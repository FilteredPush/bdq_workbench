/** DatasetInputDescription.java
 *
 * Overview of the input tables and the dataset view a run's records were built from.
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

import java.util.List;

/**
 * Describes how the records a run executed against were constructed from the input dataset.
 *
 * <p>Captured at ingest time, when the input tables, their record counts, and the dataset view
 * (if any) are still known, and carried on {@link RecordDataset} so reports can describe the
 * view the tests ran over without re-reading the input. This is an overview: each table keeps its
 * column names only so reports can work out which tables supplied terms the run's tests bound to;
 * reports are expected to summarize them, not list them.
 *
 * @param viewMode how the input tables were turned into execution records
 * @param viewSource human-readable origin of the view (a view file, a built-in view, or automatic
 *     relational ingest); {@code ""} when not applicable
 * @param grainTable the table whose rows define the execution (core) records; {@code ""} when
 *     unknown
 * @param viewRecordCount the number of records the view produced, before any record filtering
 * @param tables every table the input offered, in declaration order
 * @param relationships every relationship discovered between input tables
 * @param viewRelations the related tables the view included, relative to the grain table
 * @param grainMappedTerms for a view-built dataset, the terms mapped directly from the grain
 *     table; empty otherwise
 * @param syntheticMarkers input records carrying the BDQ markers for synthetic, modified, or
 *     example data, scanned from the raw input rows
 */
public record DatasetInputDescription(
		ViewMode viewMode,
		String viewSource,
		String grainTable,
		int viewRecordCount,
		List<InputTable> tables,
		List<RelationshipSchema> relationships,
		List<ViewRelation> viewRelations,
		List<String> grainMappedTerms,
		SyntheticDataMarkers syntheticMarkers) {

	private static final DatasetInputDescription NONE = new DatasetInputDescription(
			ViewMode.UNKNOWN, "", "", 0, List.of(), List.of(), List.of(), List.of());

	/**
	 * Creates a description with no synthetic-data scan.
	 *
	 * @param viewMode how the input tables were turned into execution records
	 * @param viewSource human-readable origin of the view
	 * @param grainTable the table whose rows define the execution records
	 * @param viewRecordCount the number of records the view produced
	 * @param tables every table the input offered
	 * @param relationships every relationship discovered between input tables
	 * @param viewRelations the related tables the view included
	 * @param grainMappedTerms the terms mapped directly from the grain table
	 */
	public DatasetInputDescription(
			ViewMode viewMode,
			String viewSource,
			String grainTable,
			int viewRecordCount,
			List<InputTable> tables,
			List<RelationshipSchema> relationships,
			List<ViewRelation> viewRelations,
			List<String> grainMappedTerms) {
		this(viewMode, viewSource, grainTable, viewRecordCount, tables, relationships, viewRelations, grainMappedTerms,
				SyntheticDataMarkers.notScanned());
	}

	/**
	 * Canonical constructor; copies list components defensively and substitutes defaults for
	 * null values.
	 */
	public DatasetInputDescription {
		viewMode = viewMode == null ? ViewMode.UNKNOWN : viewMode;
		viewSource = viewSource == null ? "" : viewSource;
		grainTable = grainTable == null ? "" : grainTable;
		tables = List.copyOf(tables == null ? List.of() : tables);
		relationships = List.copyOf(relationships == null ? List.of() : relationships);
		viewRelations = List.copyOf(viewRelations == null ? List.of() : viewRelations);
		grainMappedTerms = List.copyOf(grainMappedTerms == null ? List.of() : grainMappedTerms);
		syntheticMarkers = syntheticMarkers == null ? SyntheticDataMarkers.notScanned() : syntheticMarkers;
	}

	/**
	 * Returns a copy of this description carrying a synthetic-data scan result.
	 *
	 * @param markers the scan result
	 * @return the copy
	 */
	public DatasetInputDescription withSyntheticMarkers(SyntheticDataMarkers markers) {
		return new DatasetInputDescription(viewMode, viewSource, grainTable, viewRecordCount, tables, relationships,
				viewRelations, grainMappedTerms, markers);
	}

	/**
	 * Returns the description used when ingest did not record how the dataset was built.
	 *
	 * @return an {@link ViewMode#UNKNOWN} description with no tables
	 */
	public static DatasetInputDescription none() {
		return NONE;
	}

	/**
	 * Reports whether this description carries any table information worth rendering.
	 *
	 * @return {@code true} when the view mode is known and at least one table was recorded
	 */
	public boolean isKnown() {
		return viewMode != ViewMode.UNKNOWN && !tables.isEmpty();
	}

	/**
	 * Reports whether the execution records were built through a dataset view's term mappings
	 * (rather than directly from table columns).
	 *
	 * @return {@code true} for a flattened view, or a view with any join carrying a cardinality
	 *     policy
	 */
	public boolean isViewMapped() {
		return viewMode == ViewMode.FLATTENED
				|| viewRelations.stream().anyMatch(relation -> relation.cardinalityPolicy() != null);
	}

	/**
	 * Reports whether any included relation had more than one related row for some grain record.
	 *
	 * @return {@code true} when related-row multiplicity was observed in the input
	 */
	public boolean hasObservedMultiplicity() {
		return viewRelations.stream().anyMatch(relation -> relation.coreRecordsWithMultipleRows() > 0);
	}

	/**
	 * How execution records were constructed from the input tables.
	 */
	public enum ViewMode {
		/** Not recorded by ingest. */
		UNKNOWN,

		/** One table read directly; no related tables were joined. */
		SINGLE_TABLE,

		/** Related tables were collapsed into one flat record per grain row by a dataset view. */
		FLATTENED,

		/** Related rows were retained alongside each core record, so multiplicity is preserved. */
		STRUCTURED
	}

	/**
	 * One input table, summarized.
	 *
	 * @param name the table name used in views and relationships
	 * @param rowType the detected Darwin Core row type name
	 * @param recordCount the number of rows read from the table; {@code -1} when unknown
	 * @param columns the table's column names, used to match bound terms (not for display)
	 */
	public record InputTable(String name, String rowType, int recordCount, List<String> columns) {

		/**
		 * Canonical constructor; copies columns defensively and substitutes defaults for nulls.
		 */
		public InputTable {
			name = name == null ? "" : name;
			rowType = rowType == null ? "" : rowType;
			columns = List.copyOf(columns == null ? List.of() : columns);
		}
	}

	/**
	 * One related table included in the view, with the multiplicity observed in the input.
	 *
	 * @param relationName the relation key used on {@link RecordGraph#relatedByRelation()}
	 * @param sourceTable the related table
	 * @param cardinalityPolicy for a view-built relation, the join's policy (a flattening policy, or
	 *     {@link DatasetViewCardinalityPolicy#EXPAND} when its rows were retained); {@code null}
	 *     for automatic relational ingest, where related rows are retained
	 * @param coreRecordsWithRows how many grain records had at least one related row
	 * @param coreRecordsWithMultipleRows how many grain records had more than one related row
	 * @param maxRowsPerCoreRecord the largest number of related rows seen for one grain record
	 * @param relatedRowCount the total number of related rows linked to grain records
	 * @param mappedTerms for a view-built relation, the terms mapped from this table; empty otherwise
	 */
	public record ViewRelation(
			String relationName,
			String sourceTable,
			DatasetViewCardinalityPolicy cardinalityPolicy,
			int coreRecordsWithRows,
			int coreRecordsWithMultipleRows,
			int maxRowsPerCoreRecord,
			int relatedRowCount,
			List<String> mappedTerms) {

		/**
		 * Canonical constructor; copies mapped terms defensively and substitutes defaults.
		 */
		public ViewRelation {
			relationName = relationName == null ? "" : relationName;
			sourceTable = sourceTable == null ? "" : sourceTable;
			mappedTerms = List.copyOf(mappedTerms == null ? List.of() : mappedTerms);
		}
	}
}
