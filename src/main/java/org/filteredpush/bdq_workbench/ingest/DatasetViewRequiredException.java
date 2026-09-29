/** DatasetViewRequiredException.java
 *
 * Raised when a multi-table dataset needs multiplicity-handling decisions the user has not made.
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

import java.util.List;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;

/**
 * Stops a run on a multi-table dataset whose related tables have multiplicity no one has decided
 * how to handle.
 *
 * <p>The message names each undecided table with the multiplicity observed in the data and says
 * how to supply the decisions, so a headless run fails fast with actionable instructions rather
 * than silently dropping or garbling related rows.
 */
public class DatasetViewRequiredException extends AppException {

	private static final long serialVersionUID = 1L;

	private final String grainTable;
	private final transient List<ViewRelation> undecided;

	/**
	 * @param grainTable the grain table the view is built around
	 * @param undecided the related tables still needing a multiplicity decision
	 */
	public DatasetViewRequiredException(String grainTable, List<ViewRelation> undecided) {
		super(message(grainTable, undecided));
		this.grainTable = grainTable;
		this.undecided = List.copyOf(undecided);
	}

	/**
	 * @return the grain table the view is built around
	 */
	public String grainTable() {
		return grainTable;
	}

	/**
	 * @return the related tables still needing a multiplicity decision
	 */
	public List<ViewRelation> undecided() {
		return undecided;
	}

	/**
	 * Builds the user-facing explanation.
	 *
	 * @param grainTable the grain table
	 * @param undecided the undecided tables
	 * @return the message
	 */
	private static String message(String grainTable, List<ViewRelation> undecided) {
		String tables = undecided.stream()
				.map(relation -> "  - " + relation.sourceTable() + ": up to " + relation.maxRowsPerCoreRecord()
						+ " rows per " + grainTable + " record (" + relation.coreRecordsWithMultipleRows()
						+ " records have more than one)")
				.collect(Collectors.joining("\n"));
		String example = undecided.stream()
				.map(relation -> "--join-policy " + relation.sourceTable() + "=EXPAND")
				.collect(Collectors.joining(" "));
		return "The dataset has related tables with more than one row per " + grainTable + " record, and no "
				+ "decision was given for how to handle them:\n" + tables + "\n"
				+ "Choose a policy for each with --join-policy <table>=<EXPAND|FIRST_ROW|AGGREGATE|REJECT> "
				+ "(for example: " + example + "), run from an interactive terminal to be asked, or build a "
				+ "dataset view in the GUI and pass it with --dataset-view.\n"
				+ "EXPAND tests each related row; FIRST_ROW uses only the first; AGGREGATE joins values with \" | \"; "
				+ "REJECT leaves the terms empty where there is more than one row.";
	}
}
