/** AutomaticDatasetViews.java
 *
 * Builds the dataset view used when a multi-table dataset is run without one.
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
import org.filteredpush.bdq_workbench.model.DatasetInputDescription.ViewRelation;
import org.filteredpush.bdq_workbench.model.DatasetView;
import org.filteredpush.bdq_workbench.model.DatasetViewCardinalityPolicy;

/**
 * Builds the dataset view for a multi-table dataset run without a view file.
 *
 * <p>The grain is the table relational ingest selected (occurrence when there is one, since the
 * ratified BDQ tests are predominantly occurrence-level). Every column of the grain and of each
 * directly related table is mapped, so no data is dropped. Related tables with at most one row
 * per grain record are joined with {@link DatasetViewCardinalityPolicy#FIRST_ROW}, which loses
 * nothing there. Every table with more than one row for some grain record needs the user's
 * decision: from the supplied policies, then from the {@link JoinPolicyResolver}; if any remain
 * undecided, a {@link DatasetViewRequiredException} stops the run.
 */
final class AutomaticDatasetViews {

	private AutomaticDatasetViews() {
	}

	/**
	 * Builds the automatic view.
	 *
	 * @param relational relational ingest result for the grain table
	 * @param policies user-supplied join policies keyed by related table name (case-insensitive)
	 * @param resolver asked for policies still missing for tables with multiplicity
	 * @return the view
	 * @throws DatasetViewRequiredException if a table with multiplicity remains undecided
	 * @throws AppException if a supplied policy names a table not directly related to the grain
	 */
	static DatasetView build(
			RelationalIngestResult relational,
			Map<String, DatasetViewCardinalityPolicy> policies,
			JoinPolicyResolver resolver) {
		DatasetViewSuggester suggester = new DatasetViewSuggester();
		String grain = relational.coreTable();
		List<DatasetViewSuggester.JoinCandidate> candidates = suggester.joinCandidates(relational.schema(), grain);
		Map<String, DatasetViewCardinalityPolicy> supplied = lowerCaseKeys(policies);
		rejectUnknownTables(grain, candidates, supplied);
		Map<String, DatasetViewCardinalityPolicy> decided = new LinkedHashMap<>();
		List<ViewRelation> undecided = new ArrayList<>();
		for (DatasetViewSuggester.JoinCandidate candidate : candidates) {
			String table = candidate.sourceTable();
			DatasetViewCardinalityPolicy policy = supplied.get(table.toLowerCase());
			ViewRelation relation = relational.measureRelation(candidate.relationName(), table, policy, List.of());
			if (policy != null) {
				decided.put(table, policy);
			} else if (relation.maxRowsPerCoreRecord() <= 1) {
				decided.put(table, DatasetViewCardinalityPolicy.FIRST_ROW);
			} else {
				undecided.add(relation);
			}
		}
		if (!undecided.isEmpty()) {
			Map<String, DatasetViewCardinalityPolicy> answered = lowerCaseKeys(resolver.resolve(grain, undecided));
			List<ViewRelation> stillUndecided = new ArrayList<>();
			for (ViewRelation relation : undecided) {
				DatasetViewCardinalityPolicy policy = answered.get(relation.sourceTable().toLowerCase());
				if (policy == null) {
					stillUndecided.add(relation);
				} else {
					decided.put(relation.sourceTable(), policy);
				}
			}
			if (!stillUndecided.isEmpty()) {
				throw new DatasetViewRequiredException(grain, stillUndecided);
			}
		}
		return suggester.suggest(relational.schema(), grain, List.of(), decided, true);
	}

	/**
	 * Describes the policies a view applies, for the report's view source.
	 *
	 * @param view the view
	 * @return a summary such as {@code "event=FIRST_ROW, identification=EXPAND"}
	 */
	static String describePolicies(DatasetView view) {
		return view.joins().stream()
				.map(join -> join.sourceTable() + "=" + join.cardinalityPolicy().name())
				.collect(Collectors.joining(", "));
	}

	/**
	 * Fails when a supplied policy names a table the grain cannot join.
	 *
	 * @param grain the grain table
	 * @param candidates the grain's join candidates
	 * @param supplied supplied policies keyed by lower-cased table name
	 */
	private static void rejectUnknownTables(
			String grain,
			List<DatasetViewSuggester.JoinCandidate> candidates,
			Map<String, DatasetViewCardinalityPolicy> supplied) {
		List<String> joinable = candidates.stream().map(DatasetViewSuggester.JoinCandidate::sourceTable).toList();
		for (String table : supplied.keySet()) {
			if (joinable.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(table))) {
				throw new AppException("Join policy given for table '" + table + "', which is not directly related to "
						+ "the grain table " + grain + "; related tables: "
						+ (joinable.isEmpty() ? "none" : String.join(", ", joinable)));
			}
		}
	}

	/**
	 * Copies a policy map with lower-cased table-name keys.
	 *
	 * @param policies the policies
	 * @return the copy
	 */
	private static Map<String, DatasetViewCardinalityPolicy> lowerCaseKeys(
			Map<String, DatasetViewCardinalityPolicy> policies) {
		Map<String, DatasetViewCardinalityPolicy> copy = new LinkedHashMap<>();
		(policies == null ? Map.<String, DatasetViewCardinalityPolicy>of() : policies)
				.forEach((table, policy) -> copy.put(table.toLowerCase(), policy));
		return copy;
	}
}
