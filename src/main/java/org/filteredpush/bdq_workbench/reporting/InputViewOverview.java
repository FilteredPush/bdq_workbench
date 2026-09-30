/** InputViewOverview.java
 *
 * Report-side overview of the input tables and dataset view a run executed over, shared by the structured HTML and Markdown reports.
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
package org.filteredpush.bdq_workbench.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.DarwinCoreTermResolver;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.InputTable;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewMode;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.RelationshipSchema;

/**
 * Combines a run's {@link DatasetInputDescription} with its test bindings to explain, at an
 * overview level, which input tables the tests ran over and how the view was built.
 *
 * <p>Each input table is assigned a {@link TableRole}. A table counts as supplying a test's input
 * when it provides one of the Darwin Core terms a binding declares as {@code ACTED_UPON} or
 * {@code CONSULTED}: for a flattened view, through a view mapping from that table; otherwise,
 * through one of the table's columns. Related tables that supply no bound term are reported as
 * ignored for lacking test bindings — they contributed nothing any test read — and tables that
 * carry bound terms the view did not take from them (unrelated to the grain table, or not joined
 * or mapped by a flattened view) are reported separately as not included.
 *
 * @param description the recorded input description
 * @param tables one overview row per input table, in declaration order
 * @param boundTermCount the number of distinct terms the run's bindings read
 * @param inputRecordCount the number of records the view produced before record filtering; the
 *     description's own counts and relation measurements cover only the records the run selected
 */
public record InputViewOverview(
		DatasetInputDescription description,
		List<TableOverview> tables,
		int boundTermCount,
		int inputRecordCount) {

	/**
	 * Canonical constructor; copies tables defensively.
	 */
	public InputViewOverview {
		tables = List.copyOf(tables);
	}

	/**
	 * Builds the overview for one execution summary.
	 *
	 * @param summary the execution summary carrying the dataset description and bindings
	 * @return the overview; {@link #isKnown()} is {@code false} when ingest recorded no description
	 */
	public static InputViewOverview from(ExecutionSummary summary) {
		DatasetInputDescription input = summary.dataset().inputDescription();
		DatasetInputDescription description = selectedRecordsOnly(input, summary);
		List<Set<String>> termsByBinding = new ArrayList<>();
		Map<String, String> displayByNormalized = new LinkedHashMap<>();
		for (ImplementationBinding binding : summary.bindings()) {
			termsByBinding.add(boundTerms(binding, displayByNormalized));
		}
		List<TableOverview> tables = new ArrayList<>();
		for (InputTable table : description.tables()) {
			tables.add(describeTable(description, table, termsByBinding, displayByNormalized));
		}
		return new InputViewOverview(description, tables, displayByNormalized.size(), input.viewRecordCount());
	}

	/**
	 * Re-measures the view over the records the run selected, when a record filter kept fewer than
	 * the view produced, so multiplicity and related-row counts describe what was tested.
	 *
	 * @param input the description recorded at ingest, over every record
	 * @param summary the run, whose dataset holds the selected records
	 * @return the description over the selected records (the input itself when nothing was filtered)
	 */
	private static DatasetInputDescription selectedRecordsOnly(DatasetInputDescription input, ExecutionSummary summary) {
		List<String> selected = summary.dataset().records().stream().map(record -> record.id()).toList();
		if (!input.isKnown() || selected.size() >= input.viewRecordCount()) {
			return input;
		}
		List<ViewRelation> relations = input.viewRelations().stream()
				.map(relation -> relation.restrictedTo(selected))
				.toList();
		return new DatasetInputDescription(input.viewMode(), input.viewSource(), input.grainTable(), selected.size(),
				input.tables(), input.relationships(), relations, input.grainMappedTerms(), input.syntheticMarkers());
	}

	/**
	 * @return {@code true} when a record filter selected fewer records than the view produced
	 */
	public boolean filtered() {
		return description.viewRecordCount() < inputRecordCount;
	}

	/**
	 * @return {@code true} when ingest recorded table information for this run
	 */
	public boolean isKnown() {
		return description.isKnown();
	}

	/**
	 * @return the tables the view read terms from, including the grain table
	 */
	public List<TableOverview> includedTables() {
		return tables.stream()
				.filter(table -> table.role() == TableRole.GRAIN || table.role() == TableRole.CONTRIBUTING)
				.toList();
	}

	/**
	 * @return the tables left out of the view because no test binding reads any of their terms
	 */
	public List<TableOverview> ignoredTables() {
		return tables.stream().filter(table -> table.role() == TableRole.IGNORED_NO_BINDINGS).toList();
	}

	/**
	 * @return the tables carrying bound terms that the view did not take from them
	 */
	public List<TableOverview> notIncludedTables() {
		return tables.stream().filter(table -> table.role() == TableRole.NOT_INCLUDED).toList();
	}

	/**
	 * Returns a short label for the view mode.
	 *
	 * @return the view mode label
	 */
	public String modeLabel() {
		return switch (description.viewMode()) {
			case SINGLE_TABLE -> "Single table";
			case FLATTENED -> "Flattened view";
			case STRUCTURED -> description.hasObservedMultiplicity()
					? "Structured view with multiplicity present"
					: "Structured view (no multiplicity observed)";
			case UNKNOWN -> "Not recorded";
		};
	}

	/**
	 * Returns a one-paragraph explanation of how execution records were built.
	 *
	 * @return the view mode explanation
	 */
	public String modeExplanation() {
		String grain = description.grainTable();
		return switch (description.viewMode()) {
			case SINGLE_TABLE -> "Tests ran directly over the rows of " + grain
					+ "; no related tables were joined.";
			case FLATTENED -> "Each row of " + grain + " became one flat record. Values from joined tables were "
					+ "collapsed into that record by each join's cardinality policy, so related-row "
					+ "multiplicity was not carried into test execution.";
			case STRUCTURED -> description.isViewMapped()
					? "Each row of " + grain + " became one core record. Tables joined with a flattening policy "
							+ "(FIRST_ROW, AGGREGATE, REJECT) were collapsed into that record; the rows of tables "
							+ "joined with EXPAND were retained, so tests whose inputs come from an expanded table "
							+ "were evaluated once per related row. Each record keeps all of its evaluations; it is "
							+ "COMPLIANT for a test only when every one of them is, and NOT_COMPLIANT when any is."
					: "Each row of " + grain + " became one core record with its related rows "
							+ "retained. Tests whose inputs come from a related table were evaluated once per related "
							+ "row. Each record keeps all of its evaluations; it is COMPLIANT for a test only when "
							+ "every one of them is, and NOT_COMPLIANT when any is.";
			case UNKNOWN -> "The ingest path did not record how the execution records were built.";
		};
	}

	/**
	 * Returns one sentence per included relation describing the multiplicity seen in the input.
	 *
	 * @return multiplicity notes, empty when no related tables were included
	 */
	public List<String> multiplicityNotes() {
		List<String> notes = new ArrayList<>();
		for (ViewRelation relation : description.viewRelations()) {
			StringBuilder note = new StringBuilder(relation.sourceTable())
					.append(": ")
					.append(relation.relatedRowCount())
					.append(" related row(s) across ")
					.append(relation.coreRecordsWithRows())
					.append(" of ")
					.append(description.viewRecordCount())
					.append(" ")
					.append(description.grainTable())
					.append(" record(s)");
			if (relation.coreRecordsWithMultipleRows() > 0) {
				note.append("; ")
						.append(relation.coreRecordsWithMultipleRows())
						.append(" had more than one (max ")
						.append(relation.maxRowsPerCoreRecord())
						.append(")");
				if (relation.cardinalityPolicy() != null && relation.cardinalityPolicy().flattens()) {
					note.append(", collapsed by ").append(relation.cardinalityPolicy().name());
				} else {
					note.append(", each evaluated separately");
				}
			} else {
				note.append("; at most one per record");
			}
			notes.add(note.toString());
		}
		return notes;
	}

	/**
	 * Collects one binding's declared input terms, keyed by their normalized local names.
	 *
	 * @param binding the binding to read
	 * @param displayByNormalized receives a display form for each normalized term
	 * @return the binding's normalized input terms
	 */
	private static Set<String> boundTerms(ImplementationBinding binding, Map<String, String> displayByNormalized) {
		Set<String> terms = new LinkedHashSet<>();
		for (BoundMethodParameter bound : binding.parameterBindings()) {
			ParameterRole role = bound.parameter().role();
			if (role != ParameterRole.ACTED_UPON && role != ParameterRole.CONSULTED) {
				continue;
			}
			addTerm(bound.parameter().source(), terms, displayByNormalized);
			addTerm(bound.resolvedSource(), terms, displayByNormalized);
		}
		return terms;
	}

	/**
	 * Adds one term, by normalized local name, to a term set.
	 *
	 * @param term the raw term, possibly {@code null}
	 * @param terms the set to add to
	 * @param displayByNormalized receives the term's display form when first seen
	 */
	private static void addTerm(String term, Set<String> terms, Map<String, String> displayByNormalized) {
		String normalized = normalize(term);
		if (normalized.isEmpty()) {
			return;
		}
		terms.add(normalized);
		displayByNormalized.putIfAbsent(normalized, DarwinCoreTermResolver.localName(term));
	}

	/**
	 * Builds the overview row for one input table.
	 *
	 * @param description the recorded input description
	 * @param table the table to describe
	 * @param termsByBinding each binding's normalized input terms
	 * @param displayByNormalized display forms for normalized terms
	 * @return the table overview
	 */
	private static TableOverview describeTable(
			DatasetInputDescription description,
			InputTable table,
			List<Set<String>> termsByBinding,
			Map<String, String> displayByNormalized) {
		boolean grain = table.name().equalsIgnoreCase(description.grainTable());
		ViewRelation relation = description.viewRelations().stream()
				.filter(candidate -> candidate.sourceTable().equalsIgnoreCase(table.name()))
				.findFirst()
				.orElse(null);
		List<String> offered = offeredTerms(description, table, grain, relation);
		Set<String> supplied = new LinkedHashSet<>();
		int testCount = 0;
		for (Set<String> bindingTerms : termsByBinding) {
			boolean used = false;
			for (String term : offered) {
				if (bindingTerms.contains(normalize(term))) {
					supplied.add(normalize(term));
					used = true;
				}
			}
			if (used) {
				testCount++;
			}
		}
		List<String> suppliedDisplay = supplied.stream()
				.map(term -> displayByNormalized.getOrDefault(term, term))
				.toList();
		int selectedRows = grain ? description.viewRecordCount() : relation == null ? -1 : relation.relatedRowCount();
		return new TableOverview(
				table.name(),
				table.rowType(),
				table.recordCount(),
				table.columns().size(),
				classify(grain, relation != null, !supplied.isEmpty(), hasBoundColumn(table, termsByBinding)),
				relationToGrain(description, table),
				suppliedDisplay,
				testCount,
				relation,
				selectedRows);
	}

	/**
	 * Lists the terms a table could supply to execution records.
	 *
	 * @param description the recorded input description
	 * @param table the table
	 * @param grain whether the table is the grain table
	 * @param relation the table's included relation, or {@code null} when not included
	 * @return the table's offered terms
	 */
	private static List<String> offeredTerms(
			DatasetInputDescription description,
			InputTable table,
			boolean grain,
			ViewRelation relation) {
		if (description.isViewMapped()) {
			if (grain) {
				return description.grainMappedTerms();
			}
			return relation == null ? List.of() : relation.mappedTerms();
		}
		return grain || relation != null ? table.columns() : List.of();
	}

	/**
	 * Reports whether any of a table's columns matches a bound term, regardless of the view.
	 *
	 * @param table the table
	 * @param termsByBinding each binding's normalized input terms
	 * @return {@code true} when some binding reads a term the table carries
	 */
	private static boolean hasBoundColumn(InputTable table, List<Set<String>> termsByBinding) {
		return table.columns().stream()
				.map(InputViewOverview::normalize)
				.anyMatch(column -> termsByBinding.stream().anyMatch(terms -> terms.contains(column)));
	}

	/**
	 * Chooses a table's role in the view.
	 *
	 * @param grain whether the table is the grain table
	 * @param included whether the view included the table as a relation
	 * @param suppliesBoundTerm whether the table supplied a bound term through the view
	 * @param hasBoundColumn whether the table carries a bound term at all
	 * @return the table's role
	 */
	private static TableRole classify(boolean grain, boolean included, boolean suppliesBoundTerm,
			boolean hasBoundColumn) {
		if (grain) {
			return TableRole.GRAIN;
		}
		if (included && suppliesBoundTerm) {
			return TableRole.CONTRIBUTING;
		}
		if (hasBoundColumn) {
			return TableRole.NOT_INCLUDED;
		}
		return TableRole.IGNORED_NO_BINDINGS;
	}

	/**
	 * Describes how a table relates to the grain table.
	 *
	 * @param description the recorded input description
	 * @param table the table
	 * @return a short relationship description
	 */
	private static String relationToGrain(DatasetInputDescription description, InputTable table) {
		String grain = description.grainTable();
		if (table.name().equalsIgnoreCase(grain)) {
			return "grain table";
		}
		for (RelationshipSchema relationship : description.relationships()) {
			if (relationship.fromTable().equalsIgnoreCase(table.name())
					&& relationship.toTable().equalsIgnoreCase(grain)) {
				return "child of " + grain + " (" + relationship.fromColumn() + " → " + relationship.toColumn() + ")";
			}
			if (relationship.toTable().equalsIgnoreCase(table.name())
					&& relationship.fromTable().equalsIgnoreCase(grain)) {
				return "parent of " + grain + " (" + relationship.fromColumn() + " → " + relationship.toColumn() + ")";
			}
		}
		return "not directly related to " + grain;
	}

	/**
	 * Normalizes a term or column name to its lower-cased local name.
	 *
	 * @param term the raw term or column name
	 * @return the normalized name, or {@code ""} for null
	 */
	private static String normalize(String term) {
		return DarwinCoreTermResolver.normalizeTerm(DarwinCoreTermResolver.localName(term));
	}

	/**
	 * A table's part in constructing the view.
	 */
	public enum TableRole {
		/** The table whose rows became the execution records. */
		GRAIN("grain table"),

		/** A related table the view included and that supplied terms tests read. */
		CONTRIBUTING("joined into view"),

		/** A table no test binding read any term from, so it was left out of the view. */
		IGNORED_NO_BINDINGS("ignored: no test bindings"),

		/** A table carrying bound terms that the view did not take them from. */
		NOT_INCLUDED("not included in view");

		private final String label;

		TableRole(String label) {
			this.label = label;
		}

		/**
		 * @return the human-readable role label
		 */
		public String label() {
			return label;
		}
	}

	/**
	 * One input table, summarized for the report.
	 *
	 * @param name the table name
	 * @param rowType the detected row type name, or {@code ""} when unknown
	 * @param recordCount the number of rows read from the table, or negative when unknown
	 * @param columnCount the number of columns the table declared
	 * @param role the table's part in the view
	 * @param relationToGrain how the table relates to the grain table
	 * @param suppliedTerms the bound terms the table supplied through the view
	 * @param testCount how many test bindings read at least one term supplied by the table
	 * @param relation the table's included relation, or {@code null} when not included
	 * @param viewRowCount the table's rows in the view's selected records (the selected grain
	 *     records, or the related rows linked to them); negative when not part of the view
	 */
	public record TableOverview(
			String name,
			String rowType,
			int recordCount,
			int columnCount,
			TableRole role,
			String relationToGrain,
			List<String> suppliedTerms,
			int testCount,
			ViewRelation relation,
			int viewRowCount) {

		/**
		 * Creates a table overview without a count of the rows in the view.
		 *
		 * @param name the table name
		 * @param rowType the detected row type name
		 * @param recordCount the rows read from the table
		 * @param columnCount the table's columns
		 * @param role the table's part in the view
		 * @param relationToGrain how the table relates to the grain table
		 * @param suppliedTerms the bound terms the table supplied
		 * @param testCount the test bindings reading the table
		 * @param relation the table's included relation, or {@code null}
		 */
		public TableOverview(String name, String rowType, int recordCount, int columnCount, TableRole role,
				String relationToGrain, List<String> suppliedTerms, int testCount, ViewRelation relation) {
			this(name, rowType, recordCount, columnCount, role, relationToGrain, suppliedTerms, testCount, relation, -1);
		}

		/**
		 * Canonical constructor; copies supplied terms defensively.
		 */
		public TableOverview {
			suppliedTerms = List.copyOf(suppliedTerms);
		}

		/**
		 * @return the record count for display: {@code "132 of 5827"} when the view uses only some
		 *     of the table's rows, the table's count otherwise, {@code "?"} when unknown
		 */
		public String recordCountLabel() {
			String total = recordCount < 0 ? "?" : Integer.toString(recordCount);
			return viewRowCount < 0 || viewRowCount == recordCount ? total : viewRowCount + " of " + total;
		}

		/**
		 * Summarizes the supplied bound terms, listing at most {@code maxListed} of them.
		 *
		 * @param maxListed the most terms to name before summarizing the rest as a count
		 * @return a comma-separated term summary, or {@code "none"} when nothing was supplied
		 */
		public String suppliedTermsSummary(int maxListed) {
			if (suppliedTerms.isEmpty()) {
				return "none";
			}
			String listed = String.join(", ", suppliedTerms.subList(0, Math.min(maxListed, suppliedTerms.size())));
			int remaining = suppliedTerms.size() - maxListed;
			return remaining > 0 ? listed + " (+" + remaining + " more)" : listed;
		}
	}
}
